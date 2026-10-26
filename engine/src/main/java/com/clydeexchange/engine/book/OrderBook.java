package com.clydeexchange.engine.book;
import com.clydeexchange.engine.model.Order;
import com.clydeexchange.engine.model.Side;

import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

public class OrderBook {
    private final TreeMap<Long, PriceLevel> bids;
    private final TreeMap<Long, PriceLevel> asks;

    public OrderBook(){
        this.bids = new TreeMap<>(Comparator.reverseOrder());
        this.asks = new TreeMap<>();
    }

    void rest (Order order){
        TreeMap<Long, PriceLevel> side = (order.getSide() == Side.BUY) ? bids : asks;
        PriceLevel level = side.get(order.getPrice());
        if (level == null){
            PriceLevel newlevel = new PriceLevel(order.getPrice());
            side.put(order.getPrice(), newlevel);
            level = newlevel;
        }
        level.add(order);
    }

    PriceLevel bestBid(){
        Map.Entry<Long, PriceLevel> best = bids.firstEntry();
        return (best == null) ? null : best.getValue();
    }

    PriceLevel bestAsk(){
        Map.Entry<Long, PriceLevel> best = asks.firstEntry();
        return (best == null) ? null : best.getValue();
    }


}

