package com.clydeexchange.server.service;

import com.clydeexchange.engine.book.BookSnapshot;
import com.clydeexchange.engine.book.MatchingEngine;
import com.clydeexchange.engine.book.OpenOrder;
import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;
import com.clydeexchange.engine.model.Trade;
import com.clydeexchange.server.account.Account;
import com.clydeexchange.server.account.AccountProperties;
import com.clydeexchange.server.account.AccountView;
import com.clydeexchange.server.account.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The single doorway into the engine.
 *
 * MatchingEngine is not thread-safe, but HTTP requests and bots arrive on many threads.
 * Every call is queued onto ONE writer thread, which is the only thread that ever touches
 * the engine, the accounts or the trade history. Callers block until their task has run, then get the result.
 * No locks in our code, and every order is processed in one well-defined sequence.
 *
 * That one sequence is also what makes the money checks safe: "can this account afford it?" and
 * "match it" happen back to back on the same thread, so nothing can change in between.
 *
 * Surviving a restart: if it is given an ExchangeStore, the service loads its saved state when it is
 * built, and keeps a note of what has changed since. The writer thread itself never touches the
 * database (that would make every order wait for the disk). Instead StateSaver asks for the changes
 * about once a second with drainChanges() and writes them on its own thread.
 */
@Service
public class ExchangeService implements OrderGateway, AutoCloseable {

    /**
     * trades are this order's own fills. restingQty is how much is now waiting (on the book, or as a stop
     * that has not triggered); whatever was neither filled nor left waiting was cancelled.
     */
    public record OrderResult(long orderId, List<TradeRecord> trades, long restingQty) {}

    /** Trading in a symbol has just been paused by its circuit breaker, after the price moved movePercent. */
    public record Halt(String symbol, double movePercent, long until) {}

    /** One row of the leaderboard, before the starting cash is known. */
    public record Standing(String accountId, long equity) {}

    private static final Logger log = LoggerFactory.getLogger(ExchangeService.class);

    private static final int TRADE_HISTORY = 500;
    private static final int MAX_OPEN_ORDERS = 200;        // per account that has to pass the risk check
    private static final int FILLS_RESTORED = 100;

    private static final long MINUTE = 60_000;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;
    private static final int MINUTE_CANDLES = 1_440;       // one day of 1-minute candles per symbol
    private static final int HOUR_CANDLES = 720;           // thirty days of 1-hour candles per symbol

    /** Everything the service keeps per symbol, besides the order book itself (which lives in the engine). */
    private static final class SymbolState {
        final Instrument instrument;
        final CircuitBreaker breaker;
        final Deque<TradeRecord> recentTrades = new ArrayDeque<>();   // newest first
        final CandleSeries minutes;
        final CandleSeries hours;
        long last;                       // 0 until the first trade ever
        long day;                        // when the current trading day began (midnight UTC); 0 before the first one
        long open, high, low, volume;    // of the current trading day; prices are 0 until its first trade
        long previousClose;              // the last price of the trading day before; 0 on the first day
        boolean unsaved;

        SymbolState(Instrument instrument, CircuitBreaker breaker, boolean persistent) {
            this.instrument = instrument;
            this.breaker = breaker;
            this.minutes = new CandleSeries(MINUTE, MINUTE_CANDLES, persistent);
            this.hours = new CandleSeries(HOUR, HOUR_CANDLES, persistent);
        }

        /** The market never closes, so a "trading day" is simply a calendar day in UTC. At midnight the day's statistics start again. */
        void startDayIfNew(long now) {
            long today = now - Math.floorMod(now, DAY);
            if (today == day) return;
            if (day != 0 && last != 0) previousClose = last;
            day = today;
            open = high = low = volume = 0;
            unsaved = true;
        }

        void record(TradeRecord trade) {
            startDayIfNew(trade.timestamp());
            if (open == 0) open = low = trade.price();
            last = trade.price();
            high = Math.max(high, trade.price());
            low = Math.min(low, trade.price());
            volume += trade.qty();
            minutes.add(trade.price(), trade.qty(), trade.timestamp());
            hours.add(trade.price(), trade.qty(), trade.timestamp());
            recentTrades.addFirst(trade);
            if (recentTrades.size() > TRADE_HISTORY) recentTrades.removeLast();
            unsaved = true;
        }
    }

    private final MatchingEngine engine;
    private final LongSupplier clock;
    private final List<Instrument> instruments;
    private final Map<String, SymbolState> states = new LinkedHashMap<>();   // keeps symbols in config order
    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final List<Consumer<TradeRecord>> tradeListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Halt>> haltListeners = new CopyOnWriteArrayList<>();

    // What has changed since the last save. Only filled in when there is a store to save to.
    private final boolean persistent;
    private final Set<String> unsavedAccounts = new LinkedHashSet<>();
    private final List<ExchangeStore.SavedFill> unsavedFills = new ArrayList<>();
    private List<OpenOrder> savedOpenOrders = List.of();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "exchange-writer");
        thread.setDaemon(true);
        return thread;
    });

    /** The one Spring uses: state comes back from the database, then any trader in the config who is new gets an account. */
    @Autowired
    public ExchangeService(MarketProperties market, AccountProperties accountProperties, ExchangeStore store) {
        this(market.instruments(),
                (market.circuitBreaker() == null) ? CircuitBreaker.Settings.OFF : market.circuitBreaker(),
                System::currentTimeMillis, store);
        if (accountProperties.startingCash() != null) accountProperties.startingCash().forEach(this::openAccount);
    }

    /** For tests that only care about matching and money: just symbols, no trading halts. */
    public ExchangeService(List<String> symbols) {
        this(symbols.stream().map(symbol -> new Instrument(symbol, symbol, "", 0)).toList(),
                CircuitBreaker.Settings.OFF, System::currentTimeMillis);
    }

    /** @param clock the time in milliseconds; passed in so tests and simulations can control it */
    public ExchangeService(List<Instrument> instruments, CircuitBreaker.Settings breaker, LongSupplier clock) {
        this(instruments, breaker, clock, null);
    }

    /** @param store where state is loaded from now and saved to later; null for an exchange that forgets everything when it stops */
    public ExchangeService(List<Instrument> instruments, CircuitBreaker.Settings breaker, LongSupplier clock, ExchangeStore store) {
        this.instruments = List.copyOf(instruments);
        this.clock = clock;
        this.persistent = store != null;
        this.engine = new MatchingEngine(instruments.stream().map(Instrument::symbol).toList());
        for (Instrument instrument : instruments) {
            states.put(instrument.symbol(), new SymbolState(instrument, new CircuitBreaker(breaker), persistent));
        }
        if (store != null) {
            long now = clock.getAsLong();
            restore(store.load(FILLS_RESTORED, now - MINUTE_CANDLES * MINUTE, now - HOUR_CANDLES * HOUR));
        }
    }

    /**
     * A trader's account: starts with cash and no shares, and can only spend what it has.
     *
     * @return true if the account was created; false if the id already existed, in which case nothing changes
     */
    public boolean openAccount(String accountId, long startingCash) {
        return open(new Account(accountId, startingCash, false, false));
    }

    /** A bot account with no buying-power checks (it may go short or overdrawn). Its positions and profit are still tracked. */
    public boolean openBotAccount(String accountId) {
        return open(new Account(accountId, 0, true, true));
    }

    /** A bot account with a fixed pot of cash, which passes the same risk check as a trader. */
    public boolean openBotAccount(String accountId, long startingCash) {
        return open(new Account(accountId, startingCash, false, true));
    }

    private boolean open(Account account) {
        return onWriter(() -> {
            if (accounts.putIfAbsent(account.id(), account) != null) return false;
            if (persistent) unsavedAccounts.add(account.id());
            return true;
        });
    }

    /** Gives an account shares it did not have to buy, valued at costPerShare. How the simulator's bots get their starting holdings. */
    public void seedShares(String accountId, String symbol, long qty, long costPerShare) {
        onWriter(() -> {
            state(symbol);
            knownAccount(accountId).depositShares(symbol, qty, costPerShare);
            if (persistent) unsavedAccounts.add(accountId);
            return null;
        });
    }

    /**
     * Listeners are called ON THE WRITER THREAD for every trade, so they must be quick and never
     * block (e.g. just add to a queue). Anything slow would hold up matching for everyone.
     */
    public void addTradeListener(Consumer<TradeRecord> listener) {
        tradeListeners.add(listener);
    }

    /** Called on the writer thread each time a symbol is halted. The same rule applies: be quick. */
    public void addHaltListener(Consumer<Halt> listener) {
        haltListeners.add(listener);
    }

    @Override
    public OrderResult placeOrder(String accountId, String symbol, Side side, OrderType type, long price, long qty) {
        return placeOrder(accountId, symbol, side, type, price, 0, qty);
    }

    /** stopPrice is only used by STOP and STOP_LIMIT orders. */
    public OrderResult placeOrder(String accountId, String symbol, Side side, OrderType type, long price, long stopPrice, long qty) {
        return onWriter(() -> {
            engine.validate(accountId, symbol, side, type, price, stopPrice, qty);
            Account account = knownAccount(accountId);
            SymbolState state = states.get(symbol);
            long now = clock.getAsLong();
            if (state.breaker.isHalted(now)) {
                long seconds = (state.breaker.haltedUntil() - now + 999) / 1000;
                throw new IllegalArgumentException("Trading in " + symbol + " is halted for another " + seconds + " seconds");
            }
            if (!account.unlimited()) checkCanAfford(account, symbol, side, type, price, qty);

            MatchingEngine.SubmitResult result = engine.submit(accountId, symbol, side, type, price, stopPrice, qty);
            List<TradeRecord> ownTrades = new ArrayList<>();
            for (Trade t : result.trades()) {
                settle(symbol, t, now);
                TradeRecord record = new TradeRecord(symbol, t.buyOrderId(), t.sellOrderId(), t.price(), t.qty(), now);
                state.record(record);
                // Trades by stop orders that this order set off are real trades, but they are not this order's fills
                if (t.buyOrderId() == result.orderId() || t.sellOrderId() == result.orderId()) ownTrades.add(record);
                for (Consumer<TradeRecord> listener : tradeListeners) listener.accept(record);

                double move = state.breaker.onTrade(t.price(), now);
                if (move != 0) {
                    Halt halt = new Halt(symbol, move, state.breaker.haltedUntil());
                    for (Consumer<Halt> listener : haltListeners) listener.accept(halt);
                }
            }
            return new OrderResult(result.orderId(), ownTrades, result.restingQty());
        });
    }

    /** Only the account that placed an order can cancel it. False if it isn't theirs, is unknown, or already filled. */
    @Override
    public boolean cancelOrder(String accountId, String symbol, long orderId) {
        return onWriter(() -> {
            OpenOrder order = engine.openOrder(symbol, orderId);
            if (order == null || !order.accountId().equals(accountId)) return false;
            return engine.cancel(symbol, orderId);
        });
    }

    @Override
    public Funds funds(String accountId, String symbol) {
        return onWriter(() -> {
            state(symbol);
            Account account = knownAccount(accountId);
            List<OpenOrder> open = engine.openOrders(accountId);
            long held = account.held(symbol);
            return new Funds(account.cash() - reservedCash(open), held, held - reservedShares(open, symbol));
        });
    }

    public BookSnapshot book(String symbol, int depth) {
        return onWriter(() -> engine.snapshot(symbol, depth));
    }

    /** Newest first. */
    public List<TradeRecord> recentTrades(String symbol, int limit) {
        return onWriter(() -> state(symbol).recentTrades.stream().limit(Math.max(0, limit)).toList());
    }

    /** Cash, positions, open orders and recent fills for one account; empty if there is no such account. */
    public Optional<AccountView> account(String accountId) {
        return onWriter(() -> Optional.ofNullable(accounts.get(accountId)).map(this::view));
    }

    /** True if this id belongs to a human trader: the account exists and is not one of the simulator's bots. */
    public boolean isTrader(String accountId) {
        return onWriter(() -> {
            Account account = accounts.get(accountId);
            return account != null && !account.bot();
        });
    }

    /** What every trader's account is worth right now (cash plus shares at the last price), richest first. Bots are left out. */
    public List<Standing> standings() {
        return onWriter(() -> accounts.values().stream()
                .filter(account -> !account.bot())
                .map(account -> new Standing(account.id(), view(account).equity()))
                .sorted(Comparator.comparingLong(Standing::equity).reversed())
                .toList());
    }

    /** One summary row per symbol, in config order: last price, today's range and volume, yesterday's close, and whether it is halted. */
    public List<MarketSummary> market() {
        return onWriter(() -> {
            long now = clock.getAsLong();
            List<MarketSummary> rows = new ArrayList<>();
            for (SymbolState s : states.values()) {
                s.startDayIfNew(now);
                Instrument i = s.instrument;
                long last = (s.last == 0) ? i.startPrice() : s.last;
                rows.add(new MarketSummary(i.symbol(), i.name(), i.sector(), last, s.open, s.high, s.low, s.volume,
                        s.previousClose, s.breaker.isHalted(now), s.breaker.haltedUntil()));
            }
            return rows;
        });
    }

    /**
     * Price history as candles, oldest first, the newest one still being built.
     * 1m, 5m and 15m are made from the last day of 1-minute candles; 1h, 4h and 1d from the last thirty days of 1-hour candles.
     */
    public List<Candle> candles(String symbol, String interval, int limit) {
        return onWriter(() -> {
            SymbolState s = state(symbol);
            List<Candle> candles = switch (interval) {
                case "1m" -> s.minutes.all();
                case "5m" -> Candle.merge(s.minutes.all(), 5 * MINUTE);
                case "15m" -> Candle.merge(s.minutes.all(), 15 * MINUTE);
                case "1h" -> s.hours.all();
                case "4h" -> Candle.merge(s.hours.all(), 4 * HOUR);
                case "1d" -> Candle.merge(s.hours.all(), DAY);
                default -> throw new IllegalArgumentException("Unknown interval " + interval + ": use 1m, 5m, 15m, 1h, 4h or 1d");
            };
            int wanted = Math.max(0, Math.min(limit, candles.size()));
            return List.copyOf(candles.subList(candles.size() - wanted, candles.size()));
        });
    }

    /** How many orders are open across the whole exchange, bots included. */
    public int openOrderCount() {
        return onWriter(engine::openOrderCount);
    }

    /**
     * Everything that has changed since this was last called, as one consistent picture, and forgets it.
     * It only copies memory, so it holds up trading for a few microseconds; the slow part, writing it
     * to the database, is done afterwards by the caller on its own thread.
     */
    public ExchangeStore.Snapshot drainChanges() {
        return onWriter(() -> {
            List<ExchangeStore.SavedAccount> changedAccounts = new ArrayList<>();
            for (String id : unsavedAccounts) changedAccounts.add(saved(accounts.get(id)));
            unsavedAccounts.clear();

            List<ExchangeStore.SavedSymbol> changedSymbols = new ArrayList<>();
            List<ExchangeStore.SavedCandle> changedCandles = new ArrayList<>();
            for (SymbolState s : states.values()) {
                String symbol = s.instrument.symbol();
                if (s.unsaved) {
                    changedSymbols.add(new ExchangeStore.SavedSymbol(symbol, s.last, s.day, s.open, s.high, s.low,
                            s.volume, s.previousClose));
                    s.unsaved = false;
                }
                for (Candle c : s.minutes.drainUnsaved()) changedCandles.add(new ExchangeStore.SavedCandle(symbol, 60, c));
                for (Candle c : s.hours.drainUnsaved()) changedCandles.add(new ExchangeStore.SavedCandle(symbol, 3_600, c));
            }

            List<OpenOrder> openOrders = traderOpenOrders();
            boolean ordersChanged = !openOrders.equals(savedOpenOrders);
            if (ordersChanged) savedOpenOrders = openOrders;

            List<ExchangeStore.SavedFill> newFills = List.copyOf(unsavedFills);
            unsavedFills.clear();

            return new ExchangeStore.Snapshot(engine.nextOrderId(), changedAccounts, changedSymbols,
                    ordersChanged ? openOrders : null, newFills, changedCandles);
        });
    }

    /** The last trade price in ticks, or 0 if the symbol has not traded yet. */
    public long lastPrice(String symbol) {
        return onWriter(() -> state(symbol).last);
    }

    public boolean isHalted(String symbol) {
        return onWriter(() -> state(symbol).breaker.isHalted(clock.getAsLong()));
    }

    public List<Instrument> instruments() {
        return instruments; // fixed at startup and read-only, so safe from any thread
    }

    public Set<String> symbols() {
        return engine.symbols(); // fixed at startup and read-only, so safe from any thread
    }

    /**
     * The pre-trade risk check: reject the order unless the account could pay for it in the worst case.
     * Cash and shares already promised to waiting orders are "reserved" and can't be used twice.
     * There is no borrowing: no buying with money you don't have, no selling shares you don't hold.
     *
     * A stop order is checked like the order it will become, and reserves the same cash or shares while
     * it waits, because once it triggers inside the engine there is no second chance to check it.
     */
    private void checkCanAfford(Account account, String symbol, Side side, OrderType type, long price, long qty) {
        List<OpenOrder> open = engine.openOrders(account.id());
        if (type != OrderType.MARKET && open.size() >= MAX_OPEN_ORDERS) {
            // Without a limit, one account could fill the server's memory with orders that never trade
            throw new IllegalArgumentException("Too many open orders (" + MAX_OPEN_ORDERS + "): cancel some first");
        }
        if (side == Side.BUY) {
            if (type == OrderType.STOP) {
                // Nobody can know what a market buy will cost at some future moment, so it can't be reserved for
                throw new IllegalArgumentException("A buy stop needs a limit price: use a STOP_LIMIT order");
            }
            long cost;
            try {
                cost = (type == OrderType.MARKET) ? engine.marketBuyCost(symbol, qty) : Math.multiplyExact(price, qty);
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("Order is too large");
            }
            long buyingPower = account.cash() - reservedCash(open);
            if (cost > buyingPower) {
                throw new IllegalArgumentException("Insufficient buying power: this order needs "
                        + pounds(cost) + " but only " + pounds(buyingPower) + " is available");
            }
        } else {
            long available = account.held(symbol) - reservedShares(open, symbol);
            if (qty > available) {
                throw new IllegalArgumentException("Insufficient shares: trying to sell " + qty + " "
                        + symbol + " but only " + Math.max(0, available) + " available");
            }
        }
    }

    private static long reservedCash(List<OpenOrder> open) {
        long reserved = 0;
        for (OpenOrder o : open) if (o.side() == Side.BUY) reserved += o.price() * o.remaining();
        return reserved;
    }

    private static long reservedShares(List<OpenOrder> open, String symbol) {
        long reserved = 0;
        for (OpenOrder o : open) if (o.side() == Side.SELL && o.symbol().equals(symbol)) reserved += o.remaining();
        return reserved;
    }

    /** Moves the cash and the shares for one trade: buyer pays and receives, seller the reverse. */
    private void settle(String symbol, Trade t, long now) {
        applyFill(accounts.get(t.buyAccountId()),
                new Account.Fill(t.buyOrderId(), symbol, Side.BUY, t.price(), t.qty(), now));
        applyFill(accounts.get(t.sellAccountId()),
                new Account.Fill(t.sellOrderId(), symbol, Side.SELL, t.price(), t.qty(), now));
    }

    private void applyFill(Account account, Account.Fill fill) {
        account.applyFill(fill);
        if (!persistent) return;
        unsavedAccounts.add(account.id());
        if (!account.bot()) unsavedFills.add(new ExchangeStore.SavedFill(account.id(), fill));
    }

    private static ExchangeStore.SavedAccount saved(Account account) {
        List<ExchangeStore.SavedPosition> positions = new ArrayList<>();
        account.positions().forEach((symbol, position) ->
                positions.add(new ExchangeStore.SavedPosition(symbol, position.qty(), position.cost())));
        return new ExchangeStore.SavedAccount(account.id(), account.bot(), account.unlimited(), account.cash(),
                account.realisedPnl(), positions);
    }

    /** Every open order that belongs to a trader rather than a bot: the ones worth saving. */
    private List<OpenOrder> traderOpenOrders() {
        List<OpenOrder> orders = new ArrayList<>();
        for (Account account : accounts.values()) {
            if (!account.bot()) orders.addAll(engine.openOrders(account.id()));
        }
        return orders;
    }

    /**
     * Puts the exchange back as it was when it last saved. Runs once, in the constructor, before the
     * writer thread has been given anything to do. Anything saved for a symbol that is no longer in
     * the config is left out.
     */
    private void restore(ExchangeStore.Snapshot saved) {
        for (ExchangeStore.SavedSymbol s : saved.symbols()) {
            SymbolState state = states.get(s.symbol());
            if (state == null) continue;
            state.last = s.last();
            state.day = s.day();
            state.open = s.open();
            state.high = s.high();
            state.low = s.low();
            state.volume = s.volume();
            state.previousClose = s.previousClose();
            if (s.last() > 0) engine.restoreLastTradePrice(s.symbol(), s.last());
        }
        for (ExchangeStore.SavedCandle c : saved.candles()) {
            SymbolState state = states.get(c.symbol());
            if (state == null) continue;
            if (c.intervalSeconds() == 60) state.minutes.restore(c.candle());
            if (c.intervalSeconds() == 3_600) state.hours.restore(c.candle());
        }

        Map<String, List<Account.Fill>> fills = new HashMap<>();
        for (ExchangeStore.SavedFill f : saved.fills()) {
            fills.computeIfAbsent(f.accountId(), id -> new ArrayList<>()).add(f.fill());
        }
        for (ExchangeStore.SavedAccount a : saved.accounts()) {
            Map<String, Position> positions = new LinkedHashMap<>();
            for (ExchangeStore.SavedPosition p : a.positions()) {
                if (states.containsKey(p.symbol())) positions.put(p.symbol(), new Position(p.qty(), p.cost()));
                else log.warn("Dropping the position of {} in {}: it is no longer traded here", a.id(), p.symbol());
            }
            accounts.put(a.id(), Account.restore(a.id(), a.unlimited(), a.bot(), a.cash(), a.realisedPnl(),
                    positions, fills.getOrDefault(a.id(), List.of())));
        }

        for (OpenOrder order : saved.openOrders()) {
            if (states.containsKey(order.symbol()) && accounts.containsKey(order.accountId())) engine.restoreOrder(order);
        }
        savedOpenOrders = traderOpenOrders();
        engine.startOrderIdsAt(saved.nextOrderId());

        if (!saved.accounts().isEmpty()) {
            log.info("Restored {} accounts, {} open orders and {} candles from the database",
                    saved.accounts().size(), savedOpenOrders.size(), saved.candles().size());
        }
    }

    private AccountView view(Account account) {
        List<OpenOrder> open = engine.openOrders(account.id());
        List<AccountView.PositionView> positions = new ArrayList<>();
        long positionsValue = 0;
        long unrealised = 0;
        for (Map.Entry<String, Position> entry : account.positions().entrySet()) {
            String symbol = entry.getKey();
            Position position = entry.getValue();
            if (position.qty() == 0) continue;
            long traded = states.get(symbol).last;
            long last = (traded == 0) ? position.averagePrice() : traded;   // positions are valued at the last trade
            long value = position.qty() * last;
            long pnl = position.unrealisedPnl(last);
            positions.add(new AccountView.PositionView(symbol, position.qty(),
                    position.qty() - reservedShares(open, symbol), position.averagePrice(), last, value, pnl));
            positionsValue += value;
            unrealised += pnl;
        }
        long reserved = reservedCash(open);
        return new AccountView(account.id(), account.cash(), reserved, account.cash() - reserved, positionsValue,
                account.cash() + positionsValue, account.realisedPnl(), unrealised, positions, open, account.fills());
    }

    private SymbolState state(String symbol) {
        SymbolState state = states.get(symbol);
        if (state == null) throw new IllegalArgumentException("Unknown symbol " + symbol);
        return state;
    }

    private Account knownAccount(String accountId) {
        Account account = accounts.get(accountId);
        if (account == null) throw new IllegalArgumentException("Unknown account " + accountId);
        return account;
    }

    private static String pounds(long ticks) {
        return String.format(Locale.UK, "£%,.2f", ticks / 100.0);
    }

    /** Runs the task on the writer thread and waits for it. Engine exceptions come back unwrapped. */
    private <T> T onWriter(Callable<T> task) {
        try {
            return writer.submit(task).get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the exchange", e);
        }
    }

    @Override
    public void close() {
        writer.shutdown();
    }
}
