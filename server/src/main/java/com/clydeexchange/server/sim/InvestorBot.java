package com.clydeexchange.server.sim;

import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;
import com.clydeexchange.server.service.OrderGateway;

import java.util.Random;

/**
 * The big, slow investor: think of a pension fund. Every so often it decides to buy or sell a large
 * amount of one stock, for reasons of its own. Sending it as one order would move the price against
 * itself, so it does what real funds do and splits the order into small slices sent a couple of
 * seconds apart.
 *
 * You can see it on the chart as a steady push in one direction for a minute or so, which fades once it stops.
 * It has limited money and never sells shares it does not hold.
 */
public class InvestorBot implements Bot {

    private static final double START_CHANCE = 1.0 / 2400;   // per tick while idle: about every 10 minutes
    private static final long MIN_ORDER_POUNDS = 25_000;
    private static final long MAX_ORDER_POUNDS = 75_000;
    private static final long SLICE_POUNDS = 2_500;
    private static final int TICKS_BETWEEN_SLICES = 8;

    private final String accountId;
    private final String symbol;
    private final OrderGateway exchange;
    private final Random random;

    private Side side;
    private long poundsLeft;   // 0 means it has nothing to do
    private long tick;

    public InvestorBot(String accountId, String symbol, OrderGateway exchange, Random random) {
        this.accountId = accountId;
        this.symbol = symbol;
        this.exchange = exchange;
        this.random = random;
    }

    @Override
    public void act(MarketState market) {
        tick++;
        if (poundsLeft == 0) {
            if (random.nextDouble() >= START_CHANCE) return;
            side = random.nextBoolean() ? Side.BUY : Side.SELL;
            poundsLeft = MIN_ORDER_POUNDS + random.nextLong(MAX_ORDER_POUNDS - MIN_ORDER_POUNDS);
        }
        if (tick % TICKS_BETWEEN_SLICES != 0) return;

        long last = market.lastPrice();
        long slicePounds = Math.min(SLICE_POUNDS, poundsLeft);
        long qty = Sizing.shares(slicePounds, last);
        OrderGateway.Funds funds = exchange.funds(accountId, symbol);
        long limit = (side == Side.BUY)
                ? funds.buyingPower() / (last + last / 50 + 1)   // leave 2% in case the price runs away
                : funds.availableShares();
        qty = Math.min(qty, limit);
        if (qty <= 0) {
            poundsLeft = 0;   // out of money or shares: give up on this order
            return;
        }
        exchange.placeOrder(accountId, symbol, side, OrderType.MARKET, 0, qty);
        poundsLeft -= slicePounds;
    }

    /** True while it is part-way through a big order. */
    boolean isWorking() {
        return poundsLeft > 0;
    }
}
