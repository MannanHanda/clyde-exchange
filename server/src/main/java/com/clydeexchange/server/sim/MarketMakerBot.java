package com.clydeexchange.server.sim;

import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;
import com.clydeexchange.server.service.OrderGateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Provides liquidity: always has buy orders just below its reference price and sell orders just above it,
 * a few levels deep. Every tick it cancels its old quotes and re-quotes around a new reference price.
 * It earns the spread when others trade against it; that is how real market makers make money.
 *
 * The important part is how the reference price moves, because this is what makes prices respond to trading:
 *
 *   - Order flow. If others bought more from it than they sold to it since the last tick, it moves its
 *     quotes up, in proportion to the money involved; if they sold, down. So buying pushes the price up
 *     and selling pushes it down ("price impact"), exactly as on a real exchange.
 *   - News is public, so it reprices straight away, though not all the way.
 *   - It has a slow, vague idea of fair value and drifts towards it. The informed traders get there
 *     much sooner; this only stops the price from being stuck somewhere silly if they run out of money.
 *
 * It also quotes a wider spread when its price has been moving a lot, as real market makers do to protect themselves.
 */
public class MarketMakerBot implements Bot {

    private static final int LEVELS = 5;
    private static final double IMPACT_PER_POUND = 1.5e-7; // £10,000 of net buying moves the quotes up 0.15%
    private static final double MAX_IMPACT = 0.02;         // per tick, however big the order
    private static final double NEWS_REACTION = 0.7;       // reprice by 70% of a news jump at once
    private static final double PULL_TO_FAIR = 0.003;      // close 0.3% of the gap to fair value each tick
    private static final double BASE_HALF_SPREAD = 0.0004; // 0.04% each side when calm
    private static final long MIN_QUOTE_POUNDS = 6_000;
    private static final long MAX_QUOTE_POUNDS = 18_000;

    private final String accountId;
    private final String symbol;
    private final OrderGateway exchange;
    private final Random random;
    private final List<Long> liveOrderIds = new ArrayList<>();

    private double reference;    // 0 until the first tick
    private long lastInventory;
    private double recentMove;   // smoothed size of its own recent price changes, as a fraction

    public MarketMakerBot(String accountId, String symbol, OrderGateway exchange, Random random) {
        this.accountId = accountId;
        this.symbol = symbol;
        this.exchange = exchange;
        this.random = random;
    }

    @Override
    public void act(MarketState market) {
        long inventory = exchange.funds(accountId, symbol).held();
        if (reference == 0) {
            reference = market.lastPrice();
        } else {
            double before = reference;
            long boughtFromMe = lastInventory - inventory;   // shares; negative if others sold to it on balance
            double impact = IMPACT_PER_POUND * boughtFromMe * reference / 100;
            reference *= 1 + Math.max(-MAX_IMPACT, Math.min(MAX_IMPACT, impact));
            reference *= Math.exp(NEWS_REACTION * market.newsJump());
            reference += PULL_TO_FAIR * (market.fairValue() - reference);
            reference = Math.max(1, reference);
            recentMove = 0.9 * recentMove + 0.1 * Math.abs(reference / before - 1);
        }
        lastInventory = inventory;

        for (long id : liveOrderIds) exchange.cancelOrder(accountId, symbol, id);   // false if already filled: fine
        liveOrderIds.clear();

        long mid = Math.round(reference);
        long halfSpread = Math.max(1, Math.round(reference * (BASE_HALF_SPREAD + 1.5 * recentMove)));
        long levelGap = Math.max(1, mid / 1000);
        for (int i = 0; i < LEVELS; i++) {
            long bid = mid - halfSpread - i * levelGap;
            long ask = mid + halfSpread + i * levelGap;
            if (bid > 0) quote(Side.BUY, bid);
            quote(Side.SELL, ask);
        }
    }

    private void quote(Side side, long price) {
        long pounds = MIN_QUOTE_POUNDS + random.nextLong(MAX_QUOTE_POUNDS - MIN_QUOTE_POUNDS);
        long qty = Sizing.shares(pounds, price);
        liveOrderIds.add(exchange.placeOrder(accountId, symbol, side, OrderType.LIMIT, price, qty).orderId());
    }
}
