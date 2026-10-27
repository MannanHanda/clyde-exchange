package com.clydeexchange.server.service;

import com.clydeexchange.engine.book.BookSnapshot;
import com.clydeexchange.engine.book.MatchingEngine;
import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;
import com.clydeexchange.engine.model.Trade;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/**
 * The single doorway into the engine.
 *
 * MatchingEngine is not thread-safe, but HTTP requests and bots arrive on many threads.
 * Every call is queued onto ONE writer thread, which is the only thread that ever touches
 * the engine or the trade history. Callers block until their task has run, then get the result.
 * No locks in our code, and every order is processed in one well-defined sequence.
 */
@Service
public class ExchangeService implements AutoCloseable {

    public record OrderResult(long orderId, List<TradeRecord> trades) {}

    private static final int TRADE_HISTORY = 500;

    private final MatchingEngine engine;
    private final Map<String, Deque<TradeRecord>> recentTrades = new HashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "exchange-writer");
        thread.setDaemon(true);
        return thread;
    });

    public ExchangeService(@Value("${exchange.symbols}") List<String> symbols) {
        this.engine = new MatchingEngine(symbols);
        for (String symbol : symbols) recentTrades.put(symbol, new ArrayDeque<>());
    }

    public OrderResult placeOrder(String symbol, Side side, OrderType type, long price, long qty) {
        return onWriter(() -> {
            MatchingEngine.SubmitResult result = engine.submit(symbol, side, type, price, qty);
            long now = System.currentTimeMillis();
            List<TradeRecord> trades = new ArrayList<>();
            for (Trade t : result.trades()) {
                TradeRecord record = new TradeRecord(symbol, t.buyOrderId(), t.sellOrderId(), t.price(), t.qty(), now);
                trades.add(record);
                remember(record);
            }
            return new OrderResult(result.orderId(), trades);
        });
    }

    public boolean cancelOrder(String symbol, long orderId) {
        return onWriter(() -> engine.cancel(symbol, orderId));
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

    public Set<String> symbols() {
        return engine.symbols(); // fixed at startup and read-only, so safe from any thread
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
