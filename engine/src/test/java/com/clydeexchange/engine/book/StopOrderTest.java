package com.clydeexchange.engine.book;

import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;
import com.clydeexchange.engine.model.Trade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StopOrderTest {

    private final MatchingEngine engine = new MatchingEngine(List.of("MANN"));

    /** Sets the last traded price by trading one share at it. */
    private void tradeAt(long price) {
        engine.submit("maker", "MANN", Side.SELL, OrderType.LIMIT, price, 1);
        engine.submit("taker", "MANN", Side.BUY, OrderType.LIMIT, price, 1);
    }

    @BeforeEach
    void lastPriceIs100() {
        tradeAt(100);
    }

    @Test
    void aStopWaitsOffTheBookUntilThePriceReachesIt() {
        MatchingEngine.SubmitResult stop = engine.submit("alice", "MANN", Side.SELL, OrderType.STOP, 0, 95, 10);

        assertTrue(stop.trades().isEmpty());
        assertEquals(10, stop.restingQty());
        assertTrue(engine.snapshot("MANN", 5).asks().isEmpty(), "a stop must not show on the book");
        assertEquals(new OpenOrder(stop.orderId(), "alice", "MANN", Side.SELL, OrderType.STOP, 0, 95, 10, 10),
                engine.openOrder("MANN", stop.orderId()));

        tradeAt(96);   // not there yet
        assertNotNull(engine.openOrder("MANN", stop.orderId()));
    }

    @Test
    void aSellStopBecomesAMarketOrderWhenThePriceFallsToIt() {
        long stopId = engine.submit("alice", "MANN", Side.SELL, OrderType.STOP, 0, 95, 10).orderId();
        long bidId = engine.submit("maker", "MANN", Side.BUY, OrderType.LIMIT, 93, 50).orderId();   // what the stop will hit
        engine.submit("maker", "MANN", Side.BUY, OrderType.LIMIT, 95, 1);

        MatchingEngine.SubmitResult trigger = engine.submit("taker", "MANN", Side.SELL, OrderType.LIMIT, 95, 1);

        // First the triggering trade at 95, then alice's stop selling 10 at the best bid left: 93
        assertEquals(2, trigger.trades().size());
        assertEquals(new Trade(bidId, stopId, 93, 10, "maker", "alice"), trigger.trades().get(1));
        assertNull(engine.openOrder("MANN", stopId));
    }

    @Test
    void aBuyStopTriggersWhenThePriceRisesToIt() {
        long stopId = engine.submit("alice", "MANN", Side.BUY, OrderType.STOP, 0, 105, 10).orderId();
        engine.submit("maker", "MANN", Side.SELL, OrderType.LIMIT, 106, 50);
        tradeAt(104);
        assertNotNull(engine.openOrder("MANN", stopId));

        engine.submit("maker", "MANN", Side.SELL, OrderType.LIMIT, 105, 1);
        MatchingEngine.SubmitResult trigger = engine.submit("taker", "MANN", Side.BUY, OrderType.LIMIT, 105, 1);

        assertEquals(106, trigger.trades().get(1).price());
        assertEquals("alice", trigger.trades().get(1).buyAccountId());
    }

    @Test
    void aStopLimitRestsOnTheBookIfItCannotFillAtItsLimit() {
        long stopId = engine.submit("alice", "MANN", Side.SELL, OrderType.STOP_LIMIT, 94, 95, 10).orderId();
        engine.submit("maker", "MANN", Side.BUY, OrderType.LIMIT, 90, 50);   // too low for alice's limit of 94

        tradeAt(95);

        OpenOrder resting = engine.openOrder("MANN", stopId);
        assertEquals(OrderType.LIMIT, resting.type());
        assertEquals(94, resting.price());
        assertEquals(94, engine.snapshot("MANN", 5).asks().get(0).price());
    }

    @Test
    void oneStopCanSetOffTheNext() {
        long first = engine.submit("alice", "MANN", Side.SELL, OrderType.STOP, 0, 95, 5).orderId();
        long second = engine.submit("bob", "MANN", Side.SELL, OrderType.STOP, 0, 92, 5).orderId();
        engine.submit("maker", "MANN", Side.BUY, OrderType.LIMIT, 91, 5);   // alice's stop sells here, which is below bob's stop
        engine.submit("maker", "MANN", Side.BUY, OrderType.LIMIT, 88, 5);   // so bob's fires too and sells here

        tradeAt(95);

        assertNull(engine.openOrder("MANN", first));
        assertNull(engine.openOrder("MANN", second));
        assertTrue(engine.snapshot("MANN", 5).bids().isEmpty(), "both bids should have been sold into");
    }

    @Test
    void aStopCanBeCancelledAndIsListedAsAnOpenOrder() {
        long stopId = engine.submit("alice", "MANN", Side.SELL, OrderType.STOP, 0, 95, 10).orderId();
        assertEquals(1, engine.openOrders("alice").size());

        assertTrue(engine.cancel("MANN", stopId));
        assertFalse(engine.cancel("MANN", stopId));
        assertTrue(engine.openOrders("alice").isEmpty());

        engine.submit("maker", "MANN", Side.BUY, OrderType.LIMIT, 90, 50);
        tradeAt(95);
        assertEquals(50, engine.snapshot("MANN", 5).bids().get(0).qty());   // nothing was sold
    }

    @Test
    void aStopOnTheWrongSideOfTheMarketIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> engine.submit("alice", "MANN", Side.SELL, OrderType.STOP, 0, 100, 10));
        assertThrows(IllegalArgumentException.class, () -> engine.submit("alice", "MANN", Side.BUY, OrderType.STOP, 0, 100, 10));
        assertThrows(IllegalArgumentException.class, () -> engine.submit("alice", "MANN", Side.SELL, OrderType.STOP, 0, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> engine.submit("alice", "MANN", Side.SELL, OrderType.STOP_LIMIT, 0, 95, 10));
    }

    @Test
    void anOrdersOwnRemainderCanBeHitByAStopItTriggered() {
        // bob's buy trades 1 at 95 and rests 9 more; that trade fires alice's sell stop, which sells into bob's remainder
        long stopId = engine.submit("alice", "MANN", Side.SELL, OrderType.STOP, 0, 95, 4).orderId();
        engine.submit("maker", "MANN", Side.SELL, OrderType.LIMIT, 95, 1);

        MatchingEngine.SubmitResult bob = engine.submit("bob", "MANN", Side.BUY, OrderType.LIMIT, 95, 10);

        assertEquals(2, bob.trades().size());
        assertEquals(new Trade(bob.orderId(), stopId, 95, 4, "bob", "alice"), bob.trades().get(1));
        assertEquals(5, bob.restingQty());   // 10 - 1 - 4
    }
}
