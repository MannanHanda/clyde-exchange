package com.clydeexchange.engine.book;
import com.clydeexchange.engine.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class OrderBookTest {

    private Order buy(long id, long price, long qty) {
        return new Order(id, "MANN", Side.BUY, price, qty, OrderType.LIMIT);
    }

    private Order sell(long id, long price, long qty) {
        return new Order(id, "MANN", Side.SELL, price, qty, OrderType.LIMIT);
    }

    @Test
    void samePriceJoinsOneLevel(){
        OrderBook book = new OrderBook();
        book.rest(buy(1, 100, 50));
        book.rest(buy(2, 100, 30));
        assertEquals(80, book.bestBid().getTotalQty());
    }

    @Test
    void bestBidIsHighestPrice(){
        OrderBook book = new OrderBook();
        book.rest(buy(1, 98, 10));
        book.rest(buy(2, 100, 10));
        book.rest(buy(3, 99, 10));
        assertEquals(100, book.bestBid().getPrice());
    }

    @Test
    void bestAskIsLowestPrice(){
        OrderBook book = new OrderBook();
        book.rest(sell(1, 103, 10));
        book.rest(sell(2, 101, 10));
        book.rest(sell(3, 102, 10));
        assertEquals(101, book.bestAsk().getPrice());
    }

    @Test
    void emptyBookHasNoBestPrices(){
        OrderBook book = new OrderBook();
        assertNull(book.bestBid());
        assertNull(book.bestAsk());
    }
}
