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

    /**
     * restingQty is how much of the order is now waiting: on the book, or as a stop order that has
     * not triggered yet (0 if it filled or was cancelled).
     * trades is every trade this order caused, in order. Most involve the order itself, but if its
     * trades set off other people's stop orders, their trades are in the list too.
     */
    public record SubmitResult(long orderId, List<Trade> trades, long restingQty) {}

    private final Map<String, OrderBook> books = new LinkedHashMap<>(); // keeps symbols in config order
    private long nextOrderId = 1;

    public MatchingEngine(Collection<String> symbols) {
        for (String symbol : symbols) {
            books.put(symbol, new OrderBook());
        }
    }

    public SubmitResult submit(String accountId, String symbol, Side side, OrderType type, long price, long qty) {
        return submit(accountId, symbol, side, type, price, 0, qty);
    }

    /** stopPrice is only used by STOP and STOP_LIMIT orders; price is the limit price (ignored by MARKET and STOP). */
    public SubmitResult submit(String accountId, String symbol, Side side, OrderType type, long price, long stopPrice, long qty) {
        validate(accountId, symbol, side, type, price, stopPrice, qty);
        OrderBook book = books.get(symbol);
        long id = nextOrderId++;

        if (isStop(type)) {
            long limitPrice = (type == OrderType.STOP_LIMIT) ? price : 0;
            book.addStop(new OrderBook.StopOrder(id, accountId, symbol, side, stopPrice, limitPrice, qty));
            return new SubmitResult(id, List.of(), qty);
        }

        Order order = new Order(id, symbol, side, price, qty, type, accountId);
        List<Trade> trades = new ArrayList<>(book.match(order));
        trades.addAll(book.triggerStops());
        long restingQty = (book.find(id) == null) ? 0 : order.getRemaining();
        return new SubmitResult(id, trades, restingQty);
    }

    public void validate(String accountId, String symbol, Side side, OrderType type, long price, long qty) {
        validate(accountId, symbol, side, type, price, 0, qty);
    }

    /** Throws if the order is malformed. Public so the server can run the same checks before its own. */
    public void validate(String accountId, String symbol, Side side, OrderType type, long price, long stopPrice, long qty) {
        if (!books.containsKey(symbol)) throw new IllegalArgumentException("Unknown symbol " + symbol);
        if (accountId == null || accountId.isBlank()) throw new IllegalArgumentException("Account is required");
        if (side == null || type == null) throw new IllegalArgumentException("Side and type are required");
        if (qty <= 0) throw new IllegalArgumentException("Quantity must be > 0, was " + qty);
        boolean hasLimit = type == OrderType.LIMIT || type == OrderType.STOP_LIMIT;
        if (hasLimit && price <= 0) throw new IllegalArgumentException("Limit price must be > 0, was " + price);
        if (!isStop(type)) return;

        if (stopPrice <= 0) throw new IllegalArgumentException("Stop price must be > 0, was " + stopPrice);
        // A stop on the wrong side of the market would trigger at once, which is never what was meant
        long last = books.get(symbol).lastTradePrice();
        if (last == 0) return;
        if (side == Side.SELL && stopPrice >= last) throw new IllegalArgumentException("A sell stop price must be below the last traded price");
        if (side == Side.BUY && stopPrice <= last) throw new IllegalArgumentException("A buy stop price must be above the last traded price");
    }

    private static boolean isStop(OrderType type) {
        return type == OrderType.STOP || type == OrderType.STOP_LIMIT;
    }

    /** Returns true if the order was open (resting or a waiting stop) and is now removed; false if unknown or already filled. */
    public boolean cancel(String symbol, long orderId) {
        OrderBook book = books.get(symbol);
        if (book == null) throw new IllegalArgumentException("Unknown symbol " + symbol);
        return book.cancel(orderId);
    }

    /** The open order with this id (resting, or a waiting stop), or null if it is unknown, filled or cancelled. */
    public OpenOrder openOrder(String symbol, long orderId) {
        OrderBook book = books.get(symbol);
        if (book == null) throw new IllegalArgumentException("Unknown symbol " + symbol);
        return book.openOrder(orderId);
    }

    /** Every order this account has open (resting or a waiting stop), across all symbols, oldest first. */
    public List<OpenOrder> openOrders(String accountId) {
        List<OpenOrder> open = new ArrayList<>();
        for (OrderBook book : books.values()) {
            for (OpenOrder order : book.openOrders()) {
                if (order.accountId().equals(accountId)) open.add(order);
            }
        }
        open.sort(Comparator.comparingLong(OpenOrder::orderId));
        return open;
    }

    /** How many orders are open (resting or waiting stops) across all symbols. */
    public int openOrderCount() {
        int count = 0;
        for (OrderBook book : books.values()) count += book.openOrderCount();
        return count;
    }

    /** The id the next order will get. Saved by the server so ids carry on, not restart at 1, after a restart. */
    public long nextOrderId() {
        return nextOrderId;
    }

    // --- Restoring after a restart. The server calls these once, before any new order is accepted. ---

    /** Ids only ever move forward: asking for a lower number than the current one does nothing. */
    public void startOrderIdsAt(long nextOrderId) {
        this.nextOrderId = Math.max(this.nextOrderId, nextOrderId);
    }

    /** Tells a book what its last trade price was, so stop orders keep working from where they left off. */
    public void restoreLastTradePrice(String symbol, long price) {
        OrderBook book = books.get(symbol);
        if (book == null) throw new IllegalArgumentException("Unknown symbol " + symbol);
        if (price < 0) throw new IllegalArgumentException("Price must be >= 0, was " + price);
        book.restoreLastTradePrice(price);
    }

    /**
     * Puts back an order that was open before a restart, with its old id and remaining quantity.
     * It is placed straight on the book (or among the waiting stops) without matching, so this must
     * happen before anyone else has orders in: an order that was resting cannot cross an empty book.
     */
    public void restoreOrder(OpenOrder saved) {
        OrderBook book = books.get(saved.symbol());
        if (book == null) throw new IllegalArgumentException("Unknown symbol " + saved.symbol());
        if (saved.remaining() <= 0 || saved.remaining() > saved.quantity()) {
            throw new IllegalArgumentException("Bad remaining quantity " + saved.remaining() + " on order " + saved.orderId());
        }
        if (book.openOrder(saved.orderId()) != null) throw new IllegalArgumentException("Order " + saved.orderId() + " is already open");
        book.restore(saved);
        startOrderIdsAt(saved.orderId() + 1);
    }

    /** What a market buy of this size would cost right now (or the cost of the whole ask side, if smaller). */
    public long marketBuyCost(String symbol, long qty) {
        OrderBook book = books.get(symbol);
        if (book == null) throw new IllegalArgumentException("Unknown symbol " + symbol);
        return book.marketBuyCost(qty);
    }

    public BookSnapshot snapshot(String symbol, int depth) {
        OrderBook book = books.get(symbol);
        if (book == null) throw new IllegalArgumentException("Unknown symbol " + symbol);
        if (depth <= 0) throw new IllegalArgumentException("Depth must be > 0, was " + depth);
        return book.snapshot(symbol, depth);
    }

    public Set<String> symbols() {
        return Collections.unmodifiableSet(books.keySet());
    }
}
