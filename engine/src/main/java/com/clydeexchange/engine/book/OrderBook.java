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

    boolean cancel(long orderId) {
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

    Collection<Order> restingOrders() {
        return Collections.unmodifiableCollection(ordersById.values());
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
