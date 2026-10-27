package com.clydeexchange.server.sim;

import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;
import com.clydeexchange.server.service.OrderGateway;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/**
 * Trades for no particular reason, like retail flow. Each tick it may send a market order
 * (which creates trades against the market maker) or a limit order near fair value (which adds
 * depth and sometimes crosses). Its limit orders are cancelled after a while so the book
 * doesn't fill up with stale prices.
 */
public class NoiseTraderBot implements Bot {

    private static final int ORDER_LIFETIME_TICKS = 20;

    private record LiveOrder(long id, long placedAtTick) {}

    private final String symbol;
    private final OrderGateway exchange;
    private final Random random;
    private final double activity;   // chance of acting on a given tick
    private final Deque<LiveOrder> liveOrders = new ArrayDeque<>();
    private long tick = 0;

    public NoiseTraderBot(String symbol, OrderGateway exchange, Random random, double activity) {
        this.symbol = symbol;
        this.exchange = exchange;
        this.random = random;
        this.activity = activity;
    }

    @Override
    public void act(long fairValue) {
        tick++;
        cancelStaleOrders();
        if (random.nextDouble() >= activity) return;

        Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
        long qty = 5 + random.nextInt(36);
        if (random.nextDouble() < 0.6) {
            exchange.placeOrder(symbol, side, OrderType.MARKET, 0, qty);
        } else {
            long offset = random.nextInt((int) Math.max(2, fairValue / 500));   // within ~0.2%
            long price = Math.max(1, (side == Side.BUY) ? fairValue - offset : fairValue + offset);
            long id = exchange.placeOrder(symbol, side, OrderType.LIMIT, price, qty).orderId();
            liveOrders.addLast(new LiveOrder(id, tick));
        }
    }

    private void cancelStaleOrders() {
        while (!liveOrders.isEmpty() && tick - liveOrders.peekFirst().placedAtTick() > ORDER_LIFETIME_TICKS) {
            exchange.cancelOrder(symbol, liveOrders.pollFirst().id());
        }
    }
}
