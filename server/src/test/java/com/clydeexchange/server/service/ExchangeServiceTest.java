package com.clydeexchange.server.service;

import com.clydeexchange.engine.book.BookSnapshot;
import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;
import com.clydeexchange.server.account.AccountView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class ExchangeServiceTest {

    private final ExchangeService exchange = new ExchangeService(List.of("MANN", "CLYD"));

    @BeforeEach
    void openAccounts() {
        exchange.openBotAccount("maker");      // unlimited: provides whatever liquidity a test needs
        exchange.openBotAccount("taker");
        exchange.openAccount("alice", 10_000); // a real trader with £100.00 and no shares
    }

    @AfterEach
    void shutDown() {
        exchange.close();
    }

    private AccountView account(String id) {
        return exchange.account(id).orElseThrow();
    }

    @Test
    void tradesAreRecordedWithSymbolNewestFirst() {
        exchange.placeOrder("maker", "MANN", Side.SELL, OrderType.LIMIT, 100, 10);
        exchange.placeOrder("taker", "MANN", Side.BUY, OrderType.LIMIT, 100, 4);
        exchange.placeOrder("taker", "MANN", Side.BUY, OrderType.LIMIT, 100, 6);

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
                () -> exchange.placeOrder("maker", "MANN", Side.BUY, OrderType.LIMIT, 100, 0));
    }

    @Test
    void cancelGoesThroughToTheEngine() {
        long id = exchange.placeOrder("maker", "MANN", Side.SELL, OrderType.LIMIT, 100, 10).orderId();
        assertTrue(exchange.cancelOrder("maker", "MANN", id));
        assertTrue(exchange.book("MANN", 10).asks().isEmpty());
    }

    @Test
    void onlyTheOwnerCanCancelAnOrder() {
        long id = exchange.placeOrder("maker", "MANN", Side.SELL, OrderType.LIMIT, 100, 10).orderId();
        assertFalse(exchange.cancelOrder("alice", "MANN", id));
        assertEquals(1, exchange.book("MANN", 10).asks().size());
    }

    @Test
    void unknownAccountIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> exchange.placeOrder("nobody", "MANN", Side.BUY, OrderType.LIMIT, 100, 1));
        assertTrue(exchange.account("nobody").isEmpty());
    }

    @Test
    void aTradeMovesCashAndSharesBetweenTheTwoAccounts() {
        exchange.placeOrder("maker", "MANN", Side.SELL, OrderType.LIMIT, 100, 30);
        exchange.placeOrder("alice", "MANN", Side.BUY, OrderType.LIMIT, 100, 30);

        AccountView alice = account("alice");
        assertEquals(10_000 - 3_000, alice.cash());
        assertEquals(30, alice.positions().get(0).qty());
        assertEquals(100, alice.positions().get(0).averagePrice());
        assertEquals(10_000, alice.equity());   // 7,000 cash + 30 shares worth 100 each
        assertEquals(1, alice.fills().size());

        AccountView maker = account("maker");
        assertEquals(3_000, maker.cash());
        assertEquals(-30, maker.positions().get(0).qty());   // bots may go short
    }

    @Test
    void buyingMoreThanYouCanAffordIsRejected() {
        exchange.placeOrder("maker", "MANN", Side.SELL, OrderType.LIMIT, 100, 500);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> exchange.placeOrder("alice", "MANN", Side.BUY, OrderType.LIMIT, 100, 101));

        assertTrue(e.getMessage().startsWith("Insufficient buying power"), e.getMessage());
        assertEquals(10_000, account("alice").cash());
        assertEquals(500, exchange.book("MANN", 10).asks().get(0).qty());   // nothing traded
    }

    @Test
    void restingBuyOrdersReserveCashUntilCancelled() {
        long id = exchange.placeOrder("alice", "MANN", Side.BUY, OrderType.LIMIT, 100, 60).orderId();
        assertEquals(6_000, account("alice").reservedCash());
        assertEquals(4_000, account("alice").buyingPower());

        // 60 + 50 shares at 100 would need 11,000; she only has 10,000
        assertThrows(IllegalArgumentException.class,
                () -> exchange.placeOrder("alice", "CLYD", Side.BUY, OrderType.LIMIT, 100, 50));

        exchange.cancelOrder("alice", "MANN", id);
        assertEquals(10_000, account("alice").buyingPower());
        exchange.placeOrder("alice", "CLYD", Side.BUY, OrderType.LIMIT, 100, 50);   // now it fits
    }

    @Test
    void marketBuyIsCheckedAgainstWhatItWouldReallyCost() {
        exchange.placeOrder("maker", "MANN", Side.SELL, OrderType.LIMIT, 100, 50);
        exchange.placeOrder("maker", "MANN", Side.SELL, OrderType.LIMIT, 300, 50);

        // 50 @ 100 + 50 @ 300 = 20,000: too much
        assertThrows(IllegalArgumentException.class,
                () -> exchange.placeOrder("alice", "MANN", Side.BUY, OrderType.MARKET, 0, 100));

        // 50 @ 100 + 10 @ 300 = 8,000: fine
        exchange.placeOrder("alice", "MANN", Side.BUY, OrderType.MARKET, 0, 60);
        assertEquals(2_000, account("alice").cash());
    }

    @Test
    void youCannotSellSharesYouDoNotHold() {
        assertThrows(IllegalArgumentException.class,
                () -> exchange.placeOrder("alice", "MANN", Side.SELL, OrderType.LIMIT, 100, 1));

        exchange.placeOrder("maker", "MANN", Side.SELL, OrderType.LIMIT, 100, 10);
        exchange.placeOrder("alice", "MANN", Side.BUY, OrderType.MARKET, 0, 10);

        exchange.placeOrder("alice", "MANN", Side.SELL, OrderType.LIMIT, 200, 6);   // 6 of her 10 now reserved
        assertEquals(4, account("alice").positions().get(0).available());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> exchange.placeOrder("alice", "MANN", Side.SELL, OrderType.LIMIT, 200, 5));
        assertTrue(e.getMessage().startsWith("Insufficient shares"), e.getMessage());
    }

    @Test
    void sellingAtAProfitShowsUpAsRealisedPnl() {
        exchange.placeOrder("maker", "MANN", Side.SELL, OrderType.LIMIT, 100, 10);
        exchange.placeOrder("alice", "MANN", Side.BUY, OrderType.MARKET, 0, 10);    // buys 10 @ 100
        exchange.placeOrder("maker", "MANN", Side.BUY, OrderType.LIMIT, 120, 4);
        exchange.placeOrder("alice", "MANN", Side.SELL, OrderType.MARKET, 0, 4);    // sells 4 @ 120

        AccountView alice = account("alice");
        assertEquals(80, alice.realisedPnl());             // 4 x (120 - 100)
        assertEquals(6 * 20, alice.unrealisedPnl());       // the 6 she still holds, valued at the last trade (120)
        assertEquals(10_000 + 80 + 120, alice.equity());
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
            String account = (side == Side.BUY) ? "taker" : "maker";
            done.add(clients.submit(() -> {
                start.await();
                for (int i = 0; i < ordersPerThread; i++) {
                    exchange.placeOrder(account, "MANN", side, OrderType.LIMIT, 100, 1);
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

        // ...and the money must balance too: what the buyers paid is exactly what the sellers received
        assertEquals(-400_000, account("taker").cash());
        assertEquals(400_000, account("maker").cash());
        assertEquals(4_000, account("taker").positions().get(0).qty());
    }
}
