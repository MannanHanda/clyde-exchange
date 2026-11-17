package com.clydeexchange.engine.book;
import com.clydeexchange.engine.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MatchingEngineTest {

    private final MatchingEngine engine = new MatchingEngine(List.of("MANN", "CLYD"));

    @Test
    void orderIdsGoUpByOne(){
        long first  = engine.submit("buyer", "MANN", Side.BUY, OrderType.LIMIT, 100, 10).orderId();
        long second = engine.submit("buyer", "MANN", Side.BUY, OrderType.LIMIT, 100, 10).orderId();
        assertEquals(first + 1, second);
    }

    @Test
    void crossingOrdersProduceATrade(){
        long sellId = engine.submit("seller", "MANN", Side.SELL, OrderType.LIMIT, 100, 10).orderId();
        MatchingEngine.SubmitResult buy = engine.submit("buyer", "MANN", Side.BUY, OrderType.LIMIT, 100, 10);
        assertEquals(List.of(new Trade(buy.orderId(), sellId, 100, 10, "buyer", "seller")), buy.trades());
    }

    @Test
    void eachSymbolHasItsOwnBook(){
        engine.submit("seller", "MANN", Side.SELL, OrderType.LIMIT, 100, 10);
        MatchingEngine.SubmitResult buy = engine.submit("buyer", "CLYD", Side.BUY, OrderType.LIMIT, 100, 10);
        assertTrue(buy.trades().isEmpty());
    }

    @Test
    void unknownSymbolIsRejected(){
        assertThrows(IllegalArgumentException.class,
                () -> engine.submit("buyer", "NOPE", Side.BUY, OrderType.LIMIT, 100, 10));
    }

    @Test
    void zeroOrNegativeQuantityIsRejected(){
        assertThrows(IllegalArgumentException.class,
                () -> engine.submit("buyer", "MANN", Side.BUY, OrderType.LIMIT, 100, 0));
        assertThrows(IllegalArgumentException.class,
                () -> engine.submit("buyer", "MANN", Side.BUY, OrderType.LIMIT, 100, -5));
    }

    @Test
    void limitOrderNeedsAPositivePrice(){
        assertThrows(IllegalArgumentException.class,
                () -> engine.submit("buyer", "MANN", Side.BUY, OrderType.LIMIT, 0, 10));
    }

    @Test
    void marketOrderTradesAtAnyPrice(){
        engine.submit("seller", "MANN", Side.SELL, OrderType.LIMIT, 101, 10);
        engine.submit("seller", "MANN", Side.SELL, OrderType.LIMIT, 150, 10);
        List<Trade> trades = engine.submit("buyer", "MANN", Side.BUY, OrderType.MARKET, 0, 20).trades();
        assertEquals(2, trades.size());
        assertEquals(101, trades.get(0).price());
        assertEquals(150, trades.get(1).price());
    }

    @Test
    void marketOrderLeftoverIsDroppedNotRested(){
        engine.submit("seller", "MANN", Side.SELL, OrderType.LIMIT, 100, 10);
        engine.submit("buyer", "MANN", Side.BUY, OrderType.MARKET, 0, 25);
        // if the 15 left over had rested, this sell would trade with it
        List<Trade> trades = engine.submit("seller", "MANN", Side.SELL, OrderType.LIMIT, 1, 5).trades();
        assertTrue(trades.isEmpty());
    }

    @Test
    void marketOrderIntoEmptyBookDoesNothing(){
        List<Trade> trades = engine.submit("buyer", "MANN", Side.BUY, OrderType.MARKET, 0, 10).trades();
        assertTrue(trades.isEmpty());
        List<Trade> after = engine.submit("seller", "MANN", Side.SELL, OrderType.LIMIT, 1, 10).trades();
        assertTrue(after.isEmpty());
    }

    @Test
    void cancelledOrderCanNoLongerTrade(){
        long sellId = engine.submit("seller", "MANN", Side.SELL, OrderType.LIMIT, 100, 10).orderId();
        assertTrue(engine.cancel("MANN", sellId));
        List<Trade> trades = engine.submit("buyer", "MANN", Side.BUY, OrderType.LIMIT, 100, 10).trades();
        assertTrue(trades.isEmpty());
    }

    @Test
    void cancelUnknownIdReturnsFalse(){
        assertFalse(engine.cancel("MANN", 999));
    }

    @Test
    void cancelFilledOrderReturnsFalse(){
        long sellId = engine.submit("seller", "MANN", Side.SELL, OrderType.LIMIT, 100, 10).orderId();
        engine.submit("buyer", "MANN", Side.BUY, OrderType.LIMIT, 100, 10);
        assertFalse(engine.cancel("MANN", sellId));
    }

    @Test
    void cancelTwiceReturnsFalseTheSecondTime(){
        long sellId = engine.submit("seller", "MANN", Side.SELL, OrderType.LIMIT, 100, 10).orderId();
        assertTrue(engine.cancel("MANN", sellId));
        assertFalse(engine.cancel("MANN", sellId));
    }

    @Test
    void cancelPartiallyFilledOrderRemovesOnlyWhatIsLeft(){
        long sellId = engine.submit("seller", "MANN", Side.SELL, OrderType.LIMIT, 100, 30).orderId();
        engine.submit("buyer", "MANN", Side.BUY, OrderType.LIMIT, 100, 10);
        assertTrue(engine.cancel("MANN", sellId));
        assertTrue(engine.submit("buyer", "MANN", Side.BUY, OrderType.LIMIT, 100, 5).trades().isEmpty());
    }

    @Test
    void cancelKeepsOtherOrdersInTheQueue(){
        long alice = engine.submit("seller", "MANN", Side.SELL, OrderType.LIMIT, 100, 10).orderId();
        long bob   = engine.submit("seller", "MANN", Side.SELL, OrderType.LIMIT, 100, 10).orderId();
        engine.cancel("MANN", alice);
        List<Trade> trades = engine.submit("buyer", "MANN", Side.BUY, OrderType.LIMIT, 100, 10).trades();
        assertEquals(bob, trades.get(0).sellOrderId());
    }

    @Test
    void cancelOnUnknownSymbolIsRejected(){
        assertThrows(IllegalArgumentException.class, () -> engine.cancel("NOPE", 1));
    }

    @Test
    void snapshotShowsBestLevelsFirstWithTotals(){
        engine.submit("buyer", "MANN", Side.BUY,  OrderType.LIMIT, 99, 10);
        engine.submit("buyer", "MANN", Side.BUY,  OrderType.LIMIT, 98, 5);
        engine.submit("buyer", "MANN", Side.BUY,  OrderType.LIMIT, 99, 15);
        engine.submit("seller", "MANN", Side.SELL, OrderType.LIMIT, 101, 7);
        engine.submit("seller", "MANN", Side.SELL, OrderType.LIMIT, 102, 3);

        BookSnapshot snap = engine.snapshot("MANN", 10);

        assertEquals(List.of(new BookSnapshot.Level(99, 25), new BookSnapshot.Level(98, 5)), snap.bids());
        assertEquals(List.of(new BookSnapshot.Level(101, 7), new BookSnapshot.Level(102, 3)), snap.asks());
    }

    @Test
    void snapshotRespectsDepth(){
        engine.submit("seller", "MANN", Side.SELL, OrderType.LIMIT, 101, 1);
        engine.submit("seller", "MANN", Side.SELL, OrderType.LIMIT, 102, 1);
        engine.submit("seller", "MANN", Side.SELL, OrderType.LIMIT, 103, 1);
        assertEquals(2, engine.snapshot("MANN", 2).asks().size());
    }

    @Test
    void rejectedOrderDoesNotUseUpAnId(){
        long first = engine.submit("buyer", "MANN", Side.BUY, OrderType.LIMIT, 100, 10).orderId();
        assertThrows(IllegalArgumentException.class,
                () -> engine.submit("buyer", "MANN", Side.BUY, OrderType.LIMIT, 100, 0));
        long next = engine.submit("buyer", "MANN", Side.BUY, OrderType.LIMIT, 100, 10).orderId();
        assertEquals(first + 1, next);
    }

    @Test
    void tradeRecordsWhoBoughtAndWhoSold(){
        engine.submit("seller", "MANN", Side.SELL, OrderType.LIMIT, 100, 10);
        Trade trade = engine.submit("buyer", "MANN", Side.BUY, OrderType.LIMIT, 100, 10).trades().get(0);
        assertEquals("buyer", trade.buyAccountId());
        assertEquals("seller", trade.sellAccountId());
    }

    @Test
    void orderWithoutAnAccountIsRejected(){
        assertThrows(IllegalArgumentException.class,
                () -> engine.submit(null, "MANN", Side.BUY, OrderType.LIMIT, 100, 10));
        assertThrows(IllegalArgumentException.class,
                () -> engine.submit(" ", "MANN", Side.BUY, OrderType.LIMIT, 100, 10));
    }

    @Test
    void restingQtyIsWhatIsLeftOnTheBook(){
        engine.submit("seller", "MANN", Side.SELL, OrderType.LIMIT, 100, 10);
        assertEquals(15, engine.submit("buyer", "MANN", Side.BUY, OrderType.LIMIT, 100, 25).restingQty());
        assertEquals(0, engine.submit("seller", "MANN", Side.SELL, OrderType.LIMIT, 100, 15).restingQty());
        assertEquals(0, engine.submit("buyer", "MANN", Side.BUY, OrderType.MARKET, 0, 5).restingQty());
    }

    @Test
    void anAccountCannotTradeWithItself(){
        long resting = engine.submit("alice", "MANN", Side.SELL, OrderType.LIMIT, 100, 10).orderId();

        MatchingEngine.SubmitResult crossing = engine.submit("alice", "MANN", Side.BUY, OrderType.LIMIT, 100, 10);

        assertTrue(crossing.trades().isEmpty());
        assertEquals(0, crossing.restingQty());   // the newer order is cancelled, not rested
        assertNotNull(engine.openOrder("MANN", resting));   // the older one is untouched
        assertTrue(engine.snapshot("MANN", 10).bids().isEmpty());
    }

    @Test
    void selfTradePreventionStillFillsAgainstOthersFirst(){
        engine.submit("bob",   "MANN", Side.SELL, OrderType.LIMIT, 100, 4);
        engine.submit("alice", "MANN", Side.SELL, OrderType.LIMIT, 101, 10);

        MatchingEngine.SubmitResult buy = engine.submit("alice", "MANN", Side.BUY, OrderType.LIMIT, 101, 10);

        assertEquals(1, buy.trades().size());
        assertEquals(4, buy.trades().get(0).qty());
        assertEquals(0, buy.restingQty());
    }

    @Test
    void openOrdersListsOnlyThatAccountsRestingOrders(){
        long first = engine.submit("alice", "MANN", Side.BUY, OrderType.LIMIT, 99, 10).orderId();
        engine.submit("bob", "MANN", Side.BUY, OrderType.LIMIT, 98, 10);
        long second = engine.submit("alice", "CLYD", Side.SELL, OrderType.LIMIT, 50, 30).orderId();
        engine.submit("bob", "CLYD", Side.BUY, OrderType.LIMIT, 50, 10);   // partly fills alice's sell

        List<OpenOrder> open = engine.openOrders("alice");

        assertEquals(List.of(
                new OpenOrder(first, "alice", "MANN", Side.BUY, OrderType.LIMIT, 99, 0, 10, 10),
                new OpenOrder(second, "alice", "CLYD", Side.SELL, OrderType.LIMIT, 50, 0, 30, 20)), open);
        assertTrue(engine.openOrders("nobody").isEmpty());
    }

    @Test
    void openOrderIsNullOnceFilledOrCancelled(){
        long id = engine.submit("alice", "MANN", Side.SELL, OrderType.LIMIT, 100, 10).orderId();
        assertEquals("alice", engine.openOrder("MANN", id).accountId());
        engine.cancel("MANN", id);
        assertNull(engine.openOrder("MANN", id));
    }

    @Test
    void marketBuyCostWalksTheAsks(){
        engine.submit("seller", "MANN", Side.SELL, OrderType.LIMIT, 100, 10);
        engine.submit("seller", "MANN", Side.SELL, OrderType.LIMIT, 110, 10);

        assertEquals(500, engine.marketBuyCost("MANN", 5));
        assertEquals(10 * 100 + 5 * 110, engine.marketBuyCost("MANN", 15));
        assertEquals(10 * 100 + 10 * 110, engine.marketBuyCost("MANN", 1_000));   // capped at what is there
        assertEquals(0, engine.marketBuyCost("CLYD", 10));
    }
}
