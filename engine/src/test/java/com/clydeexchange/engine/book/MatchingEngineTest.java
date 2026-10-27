package com.clydeexchange.engine.book;
import com.clydeexchange.engine.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MatchingEngineTest {

    private final MatchingEngine engine = new MatchingEngine(List.of("MANN", "CLYD"));

    @Test
    void orderIdsGoUpByOne(){
        long first  = engine.submit("MANN", Side.BUY, OrderType.LIMIT, 100, 10).orderId();
        long second = engine.submit("MANN", Side.BUY, OrderType.LIMIT, 100, 10).orderId();
        assertEquals(first + 1, second);
    }

    @Test
    void crossingOrdersProduceATrade(){
        long sellId = engine.submit("MANN", Side.SELL, OrderType.LIMIT, 100, 10).orderId();
        MatchingEngine.SubmitResult buy = engine.submit("MANN", Side.BUY, OrderType.LIMIT, 100, 10);
        assertEquals(List.of(new Trade(buy.orderId(), sellId, 100, 10)), buy.trades());
    }

    @Test
    void eachSymbolHasItsOwnBook(){
        engine.submit("MANN", Side.SELL, OrderType.LIMIT, 100, 10);
        MatchingEngine.SubmitResult buy = engine.submit("CLYD", Side.BUY, OrderType.LIMIT, 100, 10);
        assertTrue(buy.trades().isEmpty());
    }

    @Test
    void unknownSymbolIsRejected(){
        assertThrows(IllegalArgumentException.class,
                () -> engine.submit("NOPE", Side.BUY, OrderType.LIMIT, 100, 10));
    }

    @Test
    void zeroOrNegativeQuantityIsRejected(){
        assertThrows(IllegalArgumentException.class,
                () -> engine.submit("MANN", Side.BUY, OrderType.LIMIT, 100, 0));
        assertThrows(IllegalArgumentException.class,
                () -> engine.submit("MANN", Side.BUY, OrderType.LIMIT, 100, -5));
    }

    @Test
    void limitOrderNeedsAPositivePrice(){
        assertThrows(IllegalArgumentException.class,
                () -> engine.submit("MANN", Side.BUY, OrderType.LIMIT, 0, 10));
    }

    @Test
    void marketOrderTradesAtAnyPrice(){
        engine.submit("MANN", Side.SELL, OrderType.LIMIT, 101, 10);
        engine.submit("MANN", Side.SELL, OrderType.LIMIT, 150, 10);
        List<Trade> trades = engine.submit("MANN", Side.BUY, OrderType.MARKET, 0, 20).trades();
        assertEquals(2, trades.size());
        assertEquals(101, trades.get(0).price());
        assertEquals(150, trades.get(1).price());
    }

    @Test
    void marketOrderLeftoverIsDroppedNotRested(){
        engine.submit("MANN", Side.SELL, OrderType.LIMIT, 100, 10);
        engine.submit("MANN", Side.BUY, OrderType.MARKET, 0, 25);
        // if the 15 left over had rested, this sell would trade with it
        List<Trade> trades = engine.submit("MANN", Side.SELL, OrderType.LIMIT, 1, 5).trades();
        assertTrue(trades.isEmpty());
    }

    @Test
    void marketOrderIntoEmptyBookDoesNothing(){
        List<Trade> trades = engine.submit("MANN", Side.BUY, OrderType.MARKET, 0, 10).trades();
        assertTrue(trades.isEmpty());
        List<Trade> after = engine.submit("MANN", Side.SELL, OrderType.LIMIT, 1, 10).trades();
        assertTrue(after.isEmpty());
    }

    @Test
    void rejectedOrderDoesNotUseUpAnId(){
        long first = engine.submit("MANN", Side.BUY, OrderType.LIMIT, 100, 10).orderId();
        assertThrows(IllegalArgumentException.class,
                () -> engine.submit("MANN", Side.BUY, OrderType.LIMIT, 100, 0));
        long next = engine.submit("MANN", Side.BUY, OrderType.LIMIT, 100, 10).orderId();
        assertEquals(first + 1, next);
    }
}
