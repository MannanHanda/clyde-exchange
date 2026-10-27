package com.clydeexchange.engine.book;
import com.clydeexchange.engine.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class OrderBookMatchTest {

    private Order buy(long id, long price, long qty) {
        return new Order(id, "MANN", Side.BUY, price, qty, OrderType.LIMIT);
    }

    private Order sell(long id, long price, long qty) {
        return new Order(id, "MANN", Side.SELL, price, qty, OrderType.LIMIT);
    }

    @Test
    void noCrossMeansNoTradeAndOrderRests(){
        OrderBook book = new OrderBook();
        book.match(sell(1, 101, 10));

        List<Trade> trades = book.match(buy(2, 100, 10));

        assertTrue(trades.isEmpty());
        assertEquals(100, book.bestBid().getPrice());
        assertEquals(101, book.bestAsk().getPrice());
    }

    @Test
    void tradeHappensAtRestingPrice(){
        OrderBook book = new OrderBook();
        book.match(sell(1, 100, 10));

        List<Trade> trades = book.match(buy(2, 105, 10));

        assertEquals(1, trades.size());
        assertEquals(100, trades.get(0).price());
        assertEquals(10, trades.get(0).qty());
    }

    @Test
    void buySweepsAcrossLevels(){
        OrderBook book = new OrderBook();
        book.match(sell(1, 101, 30)); // Alice
        book.match(sell(2, 101, 20)); // Bob
        book.match(sell(3, 102, 40)); // Dan

        List<Trade> trades = book.match(buy(4, 102, 60));

        assertEquals(3, trades.size());
        assertEquals(new Trade(4, 1, 101, 30), trades.get(0));
        assertEquals(new Trade(4, 2, 101, 20), trades.get(1));
        assertEquals(new Trade(4, 3, 102, 10), trades.get(2));
        assertEquals(102, book.bestAsk().getPrice());   // the 101 level is gone
        assertEquals(30, book.bestAsk().getTotalQty()); // Dan has 30 left
        assertNull(book.bestBid());                     // buyer was fully filled, so nothing rests
    }

    @Test
    void remainderRestsOnTheBook(){
        OrderBook book = new OrderBook();
        book.match(sell(1, 100, 10));

        List<Trade> trades = book.match(buy(2, 100, 25));

        assertEquals(1, trades.size());
        assertEquals(15, book.bestBid().getTotalQty());
        assertNull(book.bestAsk());
    }

    @Test
    void incomingSellRecordsCorrectIds(){
        OrderBook book = new OrderBook();
        book.match(buy(1, 100, 10));

        List<Trade> trades = book.match(sell(2, 100, 10));

        assertEquals(1, trades.get(0).buyOrderId());
        assertEquals(2, trades.get(0).sellOrderId());
    }
}
