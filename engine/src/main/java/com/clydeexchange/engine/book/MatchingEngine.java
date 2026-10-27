package com.clydeexchange.engine.book;
import com.clydeexchange.engine.model.Order;
import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;
import com.clydeexchange.engine.model.Trade;

import java.util.*;

/**
 * The front door of the engine: one OrderBook per symbol, and the only place
 * order ids are handed out. Not thread-safe on purpose: the server calls it
 * from a single thread.
 */
public class MatchingEngine {

    public record SubmitResult(long orderId, List<Trade> trades) {}

    private final Map<String, OrderBook> books = new HashMap<>();
    private long nextOrderId = 1;

    public MatchingEngine(Collection<String> symbols){
        for (String symbol : symbols) {
            books.put(symbol, new OrderBook());
        }
    }

    public SubmitResult submit(String symbol, Side side, OrderType type, long price, long qty){
        OrderBook book = books.get(symbol);
        if (book == null) throw new IllegalArgumentException("Unknown symbol " + symbol);
        if (side == null || type == null) throw new IllegalArgumentException("Side and type are required");
        if (qty <= 0) throw new IllegalArgumentException("Quantity must be > 0, was " + qty);
        if (type == OrderType.LIMIT && price <= 0) throw new IllegalArgumentException("Limit price must be > 0, was " + price);

        Order order = new Order(nextOrderId++, symbol, side, price, qty, type);
        List<Trade> trades = book.match(order);
        return new SubmitResult(order.getId(), trades);
    }

    public Set<String> symbols(){
        return Collections.unmodifiableSet(books.keySet());
    }
}
