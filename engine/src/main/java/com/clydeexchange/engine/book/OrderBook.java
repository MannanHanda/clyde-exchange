package com.clydeexchange.engine.book;
import com.clydeexchange.engine.model.Order;
import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;
import com.clydeexchange.engine.model.Trade;

import java.util.*;

public class OrderBook {
    private final TreeMap<Long, PriceLevel> bids;
    private final TreeMap<Long, PriceLevel> asks;
    private final Map<Long, Order> ordersById = new HashMap<>();
    private final Map<Long, StopOrder> stops = new LinkedHashMap<>();   // oldest first
    private long lastTradePrice;                                        // 0 until the first trade

    /**
     * A stop order is not on the book and nobody can trade with it. It waits until the last trade
     * price reaches its stop price, then turns into a normal order: MARKET if limitPrice is 0, else LIMIT.
     * A sell stop triggers when the price falls to it (a "stop-loss"); a buy stop when the price rises to it.
     */
    record StopOrder(long id, String accountId, String symbol, Side side, long stopPrice, long limitPrice, long qty) {

        boolean triggeredBy(long lastPrice) {
            return (side == Side.BUY) ? lastPrice >= stopPrice : lastPrice <= stopPrice;
        }
    }

    public OrderBook() {
        this.bids = new TreeMap<>(Comparator.reverseOrder());
        this.asks = new TreeMap<>();
    }

    void rest(Order order) {
        TreeMap<Long, PriceLevel> side = (order.getSide() == Side.BUY) ? bids : asks;
        PriceLevel level = side.get(order.getPrice());
        if (level == null) {
            PriceLevel newlevel = new PriceLevel(order.getPrice());
            side.put(order.getPrice(), newlevel);
            level = newlevel;
        }
        level.add(order);
        ordersById.put(order.getId(), order);
    }

    PriceLevel bestBid() {
        Map.Entry<Long, PriceLevel> best = bids.firstEntry();
        return (best == null) ? null : best.getValue();
    }

    PriceLevel bestAsk() {
        Map.Entry<Long, PriceLevel> best = asks.firstEntry();
        return (best == null) ? null : best.getValue();
    }

    List<Trade> match(Order incoming) {
        List<Trade> trades = new ArrayList<>();
        TreeMap<Long, PriceLevel> opposite = (incoming.getSide() == Side.BUY) ? asks : bids;
        boolean wouldSelfTrade = false;

        while (!incoming.isFilled()) {
            // 1. anyone there at all?
            Map.Entry<Long, PriceLevel> best = opposite.firstEntry();
            boolean isAnyoneThere = best != null;
            if (!isAnyoneThere) break;
            PriceLevel bestLevel = best.getValue();

            // 2. do the prices cross?
            boolean doesItCross;
            if (incoming.getType() == OrderType.MARKET){
                doesItCross =true;
            }
            else{
                doesItCross = (incoming.getSide() == Side.BUY) ? (incoming.getPrice() >= bestLevel.getPrice()) : (incoming.getPrice() <= bestLevel.getPrice());
            }
            if (!doesItCross) break;

            // 3. trade with the front ticket, at the resting price
            Order resting = bestLevel.peek();
            // Self-trade prevention ("cancel newest"): an account may not trade with itself,
            // so the incoming order stops here and whatever is left of it is cancelled.
            if (resting.getAccountId().equals(incoming.getAccountId())) {
                wouldSelfTrade = true;
                break;
            }
            long buyId = (incoming.getSide() == Side.BUY) ? incoming.getId() : resting.getId();
            long sellId = (incoming.getSide() == Side.BUY) ? resting.getId() : incoming.getId();
            long qty = Math.min(incoming.getRemaining(), resting.getRemaining());
            incoming.fill(qty);
            bestLevel.fillFront(qty);
            if (resting.isFilled()) ordersById.remove(resting.getId());
            String buyAccount = (incoming.getSide() == Side.BUY) ? incoming.getAccountId() : resting.getAccountId();
            String sellAccount = (incoming.getSide() == Side.BUY) ? resting.getAccountId() : incoming.getAccountId();
            trades.add(new Trade(buyId, sellId, bestLevel.getPrice(), qty, buyAccount, sellAccount));
            lastTradePrice = bestLevel.getPrice();

            // 4. queue empty? remove it
            if (bestLevel.isEmpty()) opposite.remove(bestLevel.getPrice());
        }

        // 5. any shares left? join my side's queue
        if (!incoming.isFilled() && incoming.getType() != OrderType.MARKET && !wouldSelfTrade) rest(incoming);
        return trades;
    }

    BookSnapshot snapshot(String symbol, int depth) {
        return new BookSnapshot(symbol, topLevels(bids, depth), topLevels(asks, depth));
    }

    private static List<BookSnapshot.Level> topLevels(TreeMap<Long, PriceLevel> side, int depth) {
        List<BookSnapshot.Level> levels = new ArrayList<>();
        for (PriceLevel level : side.values()) {   // values() walks the map in sorted order, best first
            if (levels.size() == depth) break;
            levels.add(new BookSnapshot.Level(level.getPrice(), level.getTotalQty()));
        }
        return levels;
    }

    void addStop(StopOrder stop) {
        stops.put(stop.id(), stop);
    }

    /**
     * Turns every stop order the last trade price has reached into a real order and matches it.
     * Those trades move the price too, so they can set off more stops: keep going until none fire.
     * (This chain reaction is real: it is how a falling price can turn into a sudden drop.)
     */
    List<Trade> triggerStops() {
        List<Trade> trades = new ArrayList<>();
        StopOrder next;
        while ((next = nextTriggeredStop()) != null) {
            stops.remove(next.id());
            OrderType type = (next.limitPrice() > 0) ? OrderType.LIMIT : OrderType.MARKET;
            trades.addAll(match(new Order(next.id(), next.symbol(), next.side(), next.limitPrice(), next.qty(), type, next.accountId())));
        }
        return trades;
    }

    private StopOrder nextTriggeredStop() {
        if (lastTradePrice == 0) return null;
        for (StopOrder stop : stops.values()) {
            if (stop.triggeredBy(lastTradePrice)) return stop;
        }
        return null;
    }

    long lastTradePrice() {
        return lastTradePrice;
    }

    /**
     * Puts back an order that was open before a restart, exactly as it was: same id, same remaining
     * quantity, and no matching. Only valid on a book where it cannot cross anything (in practice an empty one).
     */
    void restore(OpenOrder saved) {
        if (saved.type() == OrderType.STOP || saved.type() == OrderType.STOP_LIMIT) {
            addStop(new StopOrder(saved.orderId(), saved.accountId(), saved.symbol(), saved.side(),
                    saved.stopPrice(), saved.price(), saved.remaining()));
            return;
        }
        Order order = new Order(saved.orderId(), saved.symbol(), saved.side(), saved.price(), saved.quantity(),
                saved.type(), saved.accountId());
        long alreadyFilled = saved.quantity() - saved.remaining();
        if (alreadyFilled > 0) order.fill(alreadyFilled);
        rest(order);
    }

    void restoreLastTradePrice(long price) {
        lastTradePrice = price;
    }

    int openOrderCount() {
        return ordersById.size() + stops.size();
    }

    boolean cancel(long orderId) {
        if (stops.remove(orderId) != null) return true;
        Order order = ordersById.remove(orderId);
        if (order == null) return false; // unknown, already filled, or already cancelled

        TreeMap<Long, PriceLevel> side = (order.getSide() == Side.BUY) ? bids : asks;
        PriceLevel level = side.get(order.getPrice());
        level.remove(order);
        if (level.isEmpty()) side.remove(order.getPrice());
        return true;
    }

    /** The resting order with this id, or null if it is unknown, filled or cancelled. */
    Order find(long orderId) {
        return ordersById.get(orderId);
    }

    /** The open order with this id (resting on the book, or a waiting stop), or null. */
    OpenOrder openOrder(long orderId) {
        Order order = ordersById.get(orderId);
        if (order != null) return view(order);
        StopOrder stop = stops.get(orderId);
        return (stop == null) ? null : view(stop);
    }

    /** Everything still open in this book: resting orders and waiting stops, in no particular order. */
    List<OpenOrder> openOrders() {
        List<OpenOrder> open = new ArrayList<>();
        for (Order order : ordersById.values()) open.add(view(order));
        for (StopOrder stop : stops.values()) open.add(view(stop));
        return open;
    }

    private static OpenOrder view(Order o) {
        return new OpenOrder(o.getId(), o.getAccountId(), o.getSymbol(), o.getSide(), o.getType(), o.getPrice(), 0, o.getQuantity(), o.getRemaining());
    }

    private static OpenOrder view(StopOrder s) {
        OrderType type = (s.limitPrice() > 0) ? OrderType.STOP_LIMIT : OrderType.STOP;
        return new OpenOrder(s.id(), s.accountId(), s.symbol(), s.side(), type, s.limitPrice(), s.stopPrice(), s.qty(), s.qty());
    }

    /**
     * What a market buy of this size would cost right now: walks the asks from the best price up.
     * If the book runs out first, it is the cost of everything that is there.
     */
    long marketBuyCost(long qty) {
        long cost = 0;
        long left = qty;
        for (PriceLevel level : asks.values()) {
            if (left <= 0) break;
            long take = Math.min(left, level.getTotalQty());
            cost = Math.addExact(cost, Math.multiplyExact(take, level.getPrice()));
            left -= take;
        }
        return cost;
    }

}
