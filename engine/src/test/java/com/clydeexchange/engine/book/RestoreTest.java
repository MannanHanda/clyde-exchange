package com.clydeexchange.engine.book;

import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Putting saved orders back into a fresh engine, as the server does after a restart. */
class RestoreTest {

    private final MatchingEngine engine = new MatchingEngine(List.of("MANN"));

    @Test
    void aRestoredOrderKeepsItsIdAndRemainingQuantityAndCanTrade() {
        engine.restoreOrder(new OpenOrder(500, "alice", "MANN", Side.SELL, OrderType.LIMIT, 10_000, 0, 100, 40));

        OpenOrder open = engine.openOrder("MANN", 500);
        assertEquals(100, open.quantity());
        assertEquals(40, open.remaining());

        MatchingEngine.SubmitResult buy = engine.submit("bob", "MANN", Side.BUY, OrderType.MARKET, 0, 60);
        assertEquals(1, buy.trades().size());
        assertEquals(500, buy.trades().get(0).sellOrderId());
        assertEquals(40, buy.trades().get(0).qty());   // only what was left, not the original 100
        assertNull(engine.openOrder("MANN", 500));
    }

    @Test
    void newOrderIdsCarryOnAfterTheRestoredOnes() {
        engine.restoreOrder(new OpenOrder(500, "alice", "MANN", Side.BUY, OrderType.LIMIT, 9_900, 0, 10, 10));
        assertEquals(501, engine.submit("bob", "MANN", Side.BUY, OrderType.LIMIT, 9_800, 5).orderId());

        engine.startOrderIdsAt(9_000);
        assertEquals(9_000, engine.submit("bob", "MANN", Side.BUY, OrderType.LIMIT, 9_800, 5).orderId());

        engine.startOrderIdsAt(10);   // ids never go backwards
        assertEquals(9_001, engine.nextOrderId());
    }

    @Test
    void aRestoredStopStillTriggersFromTheRestoredLastPrice() {
        engine.restoreLastTradePrice("MANN", 10_000);
        engine.restoreOrder(new OpenOrder(7, "alice", "MANN", Side.SELL, OrderType.STOP, 0, 9_500, 20, 20));
        assertEquals(OrderType.STOP, engine.openOrder("MANN", 7).type());

        // The restored last price is what new stops are checked against
        assertThrows(IllegalArgumentException.class,
                () -> engine.submit("bob", "MANN", Side.SELL, OrderType.STOP, 0, 10_100, 5));

        engine.submit("maker", "MANN", Side.BUY, OrderType.LIMIT, 9_500, 50);
        engine.submit("maker", "MANN", Side.BUY, OrderType.LIMIT, 9_400, 50);
        MatchingEngine.SubmitResult sell = engine.submit("bob", "MANN", Side.SELL, OrderType.LIMIT, 9_500, 50);

        assertEquals(2, sell.trades().size());                 // bob's trade at 9500, then alice's stop at 9400
        assertEquals(7, sell.trades().get(1).sellOrderId());
        assertEquals(9_400, sell.trades().get(1).price());
        assertEquals(1, engine.openOrderCount());              // 30 of the 9400 bid are left
    }

    @Test
    void badSavedOrdersAreRejected() {
        engine.restoreOrder(new OpenOrder(5, "alice", "MANN", Side.BUY, OrderType.LIMIT, 9_900, 0, 10, 10));

        assertThrows(IllegalArgumentException.class, () -> engine.restoreOrder(
                new OpenOrder(5, "alice", "MANN", Side.BUY, OrderType.LIMIT, 9_900, 0, 10, 10)));   // same id twice
        assertThrows(IllegalArgumentException.class, () -> engine.restoreOrder(
                new OpenOrder(6, "alice", "MANN", Side.BUY, OrderType.LIMIT, 9_900, 0, 10, 0)));    // nothing left
        assertThrows(IllegalArgumentException.class, () -> engine.restoreOrder(
                new OpenOrder(6, "alice", "NOPE", Side.BUY, OrderType.LIMIT, 9_900, 0, 10, 10)));   // unknown symbol
    }
}
