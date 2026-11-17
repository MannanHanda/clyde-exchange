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

    private static final int TRADE_HISTORY = 500;

    /** Everything the service keeps per symbol, besides the order book itself (which lives in the engine). */
    private static final class SymbolState {
        final Instrument instrument;
        final CircuitBreaker breaker;
        final Deque<TradeRecord> recentTrades = new ArrayDeque<>();   // newest first
        long last, open, high, low, volume;                           // prices are 0 until the first trade

        SymbolState(Instrument instrument, CircuitBreaker breaker) {
            this.instrument = instrument;
            this.breaker = breaker;
        }

        void record(TradeRecord trade) {
            if (open == 0) open = low = trade.price();
            last = trade.price();
            high = Math.max(high, trade.price());
            low = Math.min(low, trade.price());
            volume += trade.qty();
            recentTrades.addFirst(trade);
            if (recentTrades.size() > TRADE_HISTORY) recentTrades.removeLast();
        }
    }

    private final MatchingEngine engine;
    private final LongSupplier clock;
    private final List<Instrument> instruments;
    private final Map<String, SymbolState> states = new LinkedHashMap<>();   // keeps symbols in config order
    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final List<Consumer<TradeRecord>> tradeListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Halt>> haltListeners = new CopyOnWriteArrayList<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "exchange-writer");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public ExchangeService(MarketProperties market, AccountProperties accountProperties) {
        this(market.instruments(),
                (market.circuitBreaker() == null) ? CircuitBreaker.Settings.OFF : market.circuitBreaker(),
                System::currentTimeMillis);
        if (accountProperties.startingCash() != null) accountProperties.startingCash().forEach(this::openAccount);
    }

    /** For tests that only care about matching and money: just symbols, no trading halts. */
    public ExchangeService(List<String> symbols) {
        this(symbols.stream().map(symbol -> new Instrument(symbol, symbol, "", 0)).toList(),
                CircuitBreaker.Settings.OFF, System::currentTimeMillis);
    }

    /** @param clock the time in milliseconds; passed in so tests and simulations can control it */
    public ExchangeService(List<Instrument> instruments, CircuitBreaker.Settings breaker, LongSupplier clock) {
        this.instruments = List.copyOf(instruments);
        this.clock = clock;
        this.engine = new MatchingEngine(instruments.stream().map(Instrument::symbol).toList());
        for (Instrument instrument : instruments) {
            states.put(instrument.symbol(), new SymbolState(instrument, new CircuitBreaker(breaker)));
        }
    }

    /** A trader's account: starts with cash and no shares, and can only spend what it has. Does nothing if the id exists. */
    public void openAccount(String accountId, long startingCash) {
        onWriter(() -> accounts.putIfAbsent(accountId, new Account(accountId, startingCash, false)));
    }

    /** An account with no buying-power checks (it may go short or overdrawn). Its positions and profit are still tracked. */
    public void openBotAccount(String accountId) {
        onWriter(() -> accounts.putIfAbsent(accountId, new Account(accountId, 0, true)));
    }

    /** Gives an account shares it did not have to buy, valued at costPerShare. How the simulator's bots get their starting holdings. */
    public void seedShares(String accountId, String symbol, long qty, long costPerShare) {
        onWriter(() -> {
            state(symbol);
            knownAccount(accountId).depositShares(symbol, qty, costPerShare);
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

    /** One summary row per symbol, in config order: last price, the range and volume since the server started, and whether it is halted. */
    public List<MarketSummary> market() {
        return onWriter(() -> {
            long now = clock.getAsLong();
            List<MarketSummary> rows = new ArrayList<>();
            for (SymbolState s : states.values()) {
                Instrument i = s.instrument;
                long last = (s.last == 0) ? i.startPrice() : s.last;
                rows.add(new MarketSummary(i.symbol(), i.name(), i.sector(), last, s.open, s.high, s.low, s.volume,
                        s.breaker.isHalted(now), s.breaker.haltedUntil()));
            }
            return rows;
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
        accounts.get(t.buyAccountId())
                .applyFill(new Account.Fill(t.buyOrderId(), symbol, Side.BUY, t.price(), t.qty(), now));
        accounts.get(t.sellAccountId())
                .applyFill(new Account.Fill(t.sellOrderId(), symbol, Side.SELL, t.price(), t.qty(), now));
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
