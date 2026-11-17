package com.clydeexchange.server.sim;

import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;
import com.clydeexchange.server.service.OrderGateway;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/**
 * Trades for no particular reason, like retail flow. Each tick it may send a market order
 * (which creates trades against the market maker) or a limit order near the last price (which adds
 * depth and sometimes crosses). Its limit orders are cancelled after a while so the book
 * doesn't fill up with stale prices. It knows nothing about fair value.
 */
public class NoiseTraderBot implements Bot {

    private static final int ORDER_LIFETIME_TICKS = 20;
    private static final long MIN_POUNDS = 500;
    private static final long MAX_POUNDS = 4_000;

    private record LiveOrder(long id, long placedAtTick) {}

    private final String accountId;
    private final String symbol;
    private final OrderGateway exchange;
    private final Random random;
    private final double activity;   // chance of acting on a given tick
    private final Deque<LiveOrder> liveOrders = new ArrayDeque<>();
    private long tick = 0;

    public NoiseTraderBot(String accountId, String symbol, OrderGateway exchange, Random random, double activity) {
        this.accountId = accountId;
        this.symbol = symbol;
        this.exchange = exchange;
        this.random = random;
        this.activity = activity;
    }

    @Override
    public void act(MarketState market) {
        tick++;
        cancelStaleOrders();
        if (random.nextDouble() >= activity) return;

        long last = market.lastPrice();
        Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
        long qty = Sizing.shares(MIN_POUNDS + random.nextLong(MAX_POUNDS - MIN_POUNDS), last);
        if (random.nextDouble() < 0.6) {
            exchange.placeOrder(accountId, symbol, side, OrderType.MARKET, 0, qty);
        } else {
            long offset = random.nextInt((int) Math.max(2, last / 500));   // within ~0.2%
            long price = Math.max(1, (side == Side.BUY) ? last - offset : last + offset);
            long id = exchange.placeOrder(accountId, symbol, side, OrderType.LIMIT, price, qty).orderId();
            liveOrders.addLast(new LiveOrder(id, tick));
        }
    }

    private void cancelStaleOrders() {
        while (!liveOrders.isEmpty() && tick - liveOrders.peekFirst().placedAtTick() > ORDER_LIFETIME_TICKS) {
            exchange.cancelOrder(accountId, symbol, liveOrders.pollFirst().id());
        }
    }
}
