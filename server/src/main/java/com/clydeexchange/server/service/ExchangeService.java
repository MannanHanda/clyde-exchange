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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

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

    /** restingQty is how much is now waiting on the book; whatever was neither filled nor rested was cancelled. */
    public record OrderResult(long orderId, List<TradeRecord> trades, long restingQty) {}

    private static final int TRADE_HISTORY = 500;

    private final MatchingEngine engine;
    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final Map<String, Long> lastPrices = new HashMap<>();
    private final Map<String, Deque<TradeRecord>> recentTrades = new HashMap<>();
    private final List<Consumer<TradeRecord>> tradeListeners = new CopyOnWriteArrayList<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "exchange-writer");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public ExchangeService(@Value("${exchange.symbols}") List<String> symbols, AccountProperties accountProperties) {
        this(symbols);
        if (accountProperties.startingCash() != null) accountProperties.startingCash().forEach(this::openAccount);
    }

    public ExchangeService(List<String> symbols) {
        this.engine = new MatchingEngine(symbols);
        for (String symbol : symbols) recentTrades.put(symbol, new ArrayDeque<>());
    }

    /** A trader's account: starts with cash and no shares, and can only spend what it has. Does nothing if the id exists. */
    public void openAccount(String accountId, long startingCash) {
        onWriter(() -> accounts.putIfAbsent(accountId, new Account(accountId, startingCash, false)));
    }

    /** A simulator bot's account: no buying-power checks, but its positions and profit are still tracked. */
    public void openBotAccount(String accountId) {
        onWriter(() -> accounts.putIfAbsent(accountId, new Account(accountId, 0, true)));
    }

    /**
     * Listeners are called ON THE WRITER THREAD for every trade, so they must be quick and never
     * block (e.g. just add to a queue). Anything slow would hold up matching for everyone.
     */
    public void addTradeListener(Consumer<TradeRecord> listener) {
        tradeListeners.add(listener);
    }

    @Override
    public OrderResult placeOrder(String accountId, String symbol, Side side, OrderType type, long price, long qty) {
        return onWriter(() -> {
            engine.validate(accountId, symbol, side, type, price, qty);
            Account account = accounts.get(accountId);
            if (account == null) throw new IllegalArgumentException("Unknown account " + accountId);
            if (!account.unlimited()) checkCanAfford(account, symbol, side, type, price, qty);

            MatchingEngine.SubmitResult result = engine.submit(accountId, symbol, side, type, price, qty);
            long now = System.currentTimeMillis();
            List<TradeRecord> trades = new ArrayList<>();
            for (Trade t : result.trades()) {
                settle(symbol, t, now);
                TradeRecord record = new TradeRecord(symbol, t.buyOrderId(), t.sellOrderId(), t.price(), t.qty(), now);
                trades.add(record);
                remember(record);
                for (Consumer<TradeRecord> listener : tradeListeners) listener.accept(record);
            }
            return new OrderResult(result.orderId(), trades, result.restingQty());
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

    public BookSnapshot book(String symbol, int depth) {
        return onWriter(() -> engine.snapshot(symbol, depth));
    }

    /** Newest first. */
    public List<TradeRecord> recentTrades(String symbol, int limit) {
        return onWriter(() -> {
            Deque<TradeRecord> trades = recentTrades.get(symbol);
            if (trades == null) throw new IllegalArgumentException("Unknown symbol " + symbol);
            return trades.stream().limit(Math.max(0, limit)).toList();
        });
    }

    /** Cash, positions, open orders and recent fills for one account; empty if there is no such account. */
    public Optional<AccountView> account(String accountId) {
        return onWriter(() -> Optional.ofNullable(accounts.get(accountId)).map(this::view));
    }

    public Set<String> symbols() {
        return engine.symbols(); // fixed at startup and read-only, so safe from any thread
    }

    /**
     * The pre-trade risk check: reject the order unless the account could pay for it in the worst case.
     * Cash and shares already promised to resting orders are "reserved" and can't be used twice.
     * There is no borrowing: no buying with money you don't have, no selling shares you don't hold.
     */
    private void checkCanAfford(Account account, String symbol, Side side, OrderType type, long price, long qty) {
        List<OpenOrder> open = engine.openOrders(account.id());
        if (side == Side.BUY) {
            long cost;
            try {
                cost = (type == OrderType.LIMIT) ? Math.multiplyExact(price, qty) : engine.marketBuyCost(symbol, qty);
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
        lastPrices.put(symbol, t.price());
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
            long last = lastPrices.getOrDefault(symbol, position.averagePrice());   // positions are valued at the last trade
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

    private static String pounds(long ticks) {
        return String.format(Locale.UK, "£%,.2f", ticks / 100.0);
    }

    private void remember(TradeRecord record) {
        Deque<TradeRecord> trades = recentTrades.get(record.symbol());
        trades.addFirst(record);
        if (trades.size() > TRADE_HISTORY) trades.removeLast();
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
