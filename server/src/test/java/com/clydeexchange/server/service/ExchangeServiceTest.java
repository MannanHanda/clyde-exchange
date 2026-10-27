package com.clydeexchange.server.service;

import com.clydeexchange.engine.book.BookSnapshot;
import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class ExchangeServiceTest {

    private final ExchangeService exchange = new ExchangeService(List.of("MANN", "CLYD"));

    @AfterEach
    void shutDown() {
        exchange.close();
    }

    @Test
    void tradesAreRecordedWithSymbolNewestFirst() {
        exchange.placeOrder("MANN", Side.SELL, OrderType.LIMIT, 100, 10);
        exchange.placeOrder("MANN", Side.BUY, OrderType.LIMIT, 100, 4);
        exchange.placeOrder("MANN", Side.BUY, OrderType.LIMIT, 100, 6);

        List<TradeRecord> trades = exchange.recentTrades("MANN", 50);

        assertEquals(2, trades.size());
        assertEquals(6, trades.get(0).qty());   // newest first
        assertEquals(4, trades.get(1).qty());
        assertEquals("MANN", trades.get(0).symbol());
        assertTrue(exchange.recentTrades("CLYD", 50).isEmpty());
    }

    @Test
    void engineValidationErrorsComeBackUnwrapped() {
        // The engine throws on the writer thread; the caller must still see a plain IllegalArgumentException
        assertThrows(IllegalArgumentException.class,
                () -> exchange.placeOrder("MANN", Side.BUY, OrderType.LIMIT, 100, 0));
    }

    @Test
    void cancelGoesThroughToTheEngine() {
        long id = exchange.placeOrder("MANN", Side.SELL, OrderType.LIMIT, 100, 10).orderId();
        assertTrue(exchange.cancelOrder("MANN", id));
        assertTrue(exchange.book("MANN", 10).asks().isEmpty());
    }

    /**
     * The point of the single writer: hammer the exchange from many threads at once and the book
     * must still balance exactly. If threads called the (non-thread-safe) engine directly,
     * orders could be lost or totalQty corrupted, and this test would be flaky.
     */
    @Test
    void manyThreadsAtOnceStillBalance() throws Exception {
        int threads = 8;
        int ordersPerThread = 1_000;
        ExecutorService clients = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            Side side = (t % 2 == 0) ? Side.BUY : Side.SELL;   // half the threads buy, half sell
            done.add(clients.submit(() -> {
                start.await();
                for (int i = 0; i < ordersPerThread; i++) {
                    exchange.placeOrder("MANN", side, OrderType.LIMIT, 100, 1);
                }
                return null;
            }));
        }
        start.countDown();   // release every thread at the same moment
        for (Future<?> f : done) f.get(30, TimeUnit.SECONDS);
        clients.shutdown();

        // 4,000 buys of 1 and 4,000 sells of 1 at the same price: every share must have traded
        BookSnapshot book = exchange.book("MANN", 10);
        assertTrue(book.bids().isEmpty(), "bids left: " + book.bids());
        assertTrue(book.asks().isEmpty(), "asks left: " + book.asks());
    }
}
