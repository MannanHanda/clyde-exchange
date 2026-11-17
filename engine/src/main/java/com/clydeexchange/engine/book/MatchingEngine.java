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

    /** restingQty is how much of the order is now waiting on the book (0 if it filled or was cancelled). */
    public record SubmitResult(long orderId, List<Trade> trades, long restingQty) {}

    private final Map<String, OrderBook> books = new LinkedHashMap<>(); // keeps symbols in config order
    private long nextOrderId = 1;

    public MatchingEngine(Collection<String> symbols){
        for (String symbol : symbols) {
            books.put(symbol, new OrderBook());
        }
    }

    public SubmitResult submit(String accountId, String symbol, Side side, OrderType type, long price, long qty){
        validate(accountId, symbol, side, type, price, qty);
        OrderBook book = books.get(symbol);

        Order order = new Order(nextOrderId++, symbol, side, price, qty, type, accountId);
        List<Trade> trades = book.match(order);
        long restingQty = (book.find(order.getId()) == null) ? 0 : order.getRemaining();
        return new SubmitResult(order.getId(), trades, restingQty);
    }

    /** Throws if the order is malformed. Public so the server can run the same checks before its own. */
    public void validate(String accountId, String symbol, Side side, OrderType type, long price, long qty){
        if (!books.containsKey(symbol)) throw new IllegalArgumentException("Unknown symbol " + symbol);
        if (accountId == null || accountId.isBlank()) throw new IllegalArgumentException("Account is required");
        if (side == null || type == null) throw new IllegalArgumentException("Side and type are required");
        if (qty <= 0) throw new IllegalArgumentException("Quantity must be > 0, was " + qty);
        if (type == OrderType.LIMIT && price <= 0) throw new IllegalArgumentException("Limit price must be > 0, was " + price);
    }

    /** Returns true if the order was resting and is now removed; false if unknown or already filled. */
    public boolean cancel(String symbol, long orderId){
        OrderBook book = books.get(symbol);
        if (book == null) throw new IllegalArgumentException("Unknown symbol " + symbol);
        return book.cancel(orderId);
    }

    /** The resting order with this id, or null if it is unknown, filled or cancelled. */
    public OpenOrder openOrder(String symbol, long orderId){
        OrderBook book = books.get(symbol);
        if (book == null) throw new IllegalArgumentException("Unknown symbol " + symbol);
        Order order = book.find(orderId);
        return (order == null) ? null : view(order);
    }

    /** Every order this account has resting, across all symbols, oldest first. */
    public List<OpenOrder> openOrders(String accountId){
        List<OpenOrder> open = new ArrayList<>();
        for (OrderBook book : books.values()) {
            for (Order order : book.restingOrders()) {
                if (order.getAccountId().equals(accountId)) open.add(view(order));
            }
        }
        open.sort(Comparator.comparingLong(OpenOrder::orderId));
        return open;
    }

    /** What a market buy of this size would cost right now (or the cost of the whole ask side, if smaller). */
    public long marketBuyCost(String symbol, long qty){
        OrderBook book = books.get(symbol);
        if (book == null) throw new IllegalArgumentException("Unknown symbol " + symbol);
        return book.marketBuyCost(qty);
    }

    private static OpenOrder view(Order o){
        return new OpenOrder(o.getId(), o.getAccountId(), o.getSymbol(), o.getSide(), o.getPrice(), o.getQuantity(), o.getRemaining());
    }

    public BookSnapshot snapshot(String symbol, int depth){
        OrderBook book = books.get(symbol);
        if (book == null) throw new IllegalArgumentException("Unknown symbol " + symbol);
        if (depth <= 0) throw new IllegalArgumentException("Depth must be > 0, was " + depth);
        return book.snapshot(symbol, depth);
    }

    public Set<String> symbols(){
        return Collections.unmodifiableSet(books.keySet());
    }
}
