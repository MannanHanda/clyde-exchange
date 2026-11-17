package com.clydeexchange.server.sim;

import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;
import com.clydeexchange.server.service.OrderGateway;

/**
 * The trend follower: it knows nothing about what a stock is worth, it only watches the price.
 * It keeps two moving averages of the last price, a fast one (the last few seconds) and a slow one
 * (the last half minute). Fast above slow means the price has been rising, so it buys, a bit at a time,
 * up to a fixed amount. Fast below slow means falling, so it sells what it holds.
 *
 * Trend followers make trends go further than the news justifies, and then the value traders
 * push the price back. It has limited money and never sells shares it does not hold.
 */
public class MomentumBot implements Bot {

    private static final int FAST_TICKS = 20;
    private static final int SLOW_TICKS = 120;
    private static final double MIN_SIGNAL = 0.0015;      // the averages must be 0.15% apart before it acts
    private static final long TARGET_POUNDS = 30_000;     // how much it wants to hold in an uptrend
    private static final long POUNDS_PER_TICK = 2_000;

    private final String accountId;
    private final String symbol;
    private final OrderGateway exchange;

    private double fast;
    private double slow;
    private int ticksSeen;

    public MomentumBot(String accountId, String symbol, OrderGateway exchange) {
        this.accountId = accountId;
        this.symbol = symbol;
        this.exchange = exchange;
    }

    @Override
    public void act(MarketState market) {
        long last = market.lastPrice();
        if (ticksSeen == 0) fast = slow = last;
        fast += (last - fast) * 2 / (FAST_TICKS + 1);   // exponential moving averages: recent prices count most
        slow += (last - slow) * 2 / (SLOW_TICKS + 1);
        if (++ticksSeen < SLOW_TICKS) return;           // not enough history to call anything a trend yet

        double signal = fast / slow - 1;
        if (Math.abs(signal) < MIN_SIGNAL) return;

        OrderGateway.Funds funds = exchange.funds(accountId, symbol);
        long clip = Sizing.shares(POUNDS_PER_TICK, last);
        if (signal > 0) {
            long wanted = Sizing.shares(TARGET_POUNDS, last) - funds.held();
            long affordable = funds.buyingPower() / (last + last / 50 + 1);   // leave 2% in case the price runs away
            long qty = Math.min(clip, Math.min(wanted, affordable));
            if (qty > 0) exchange.placeOrder(accountId, symbol, Side.BUY, OrderType.MARKET, 0, qty);
        } else {
            long qty = Math.min(clip, funds.availableShares());
            if (qty > 0) exchange.placeOrder(accountId, symbol, Side.SELL, OrderType.MARKET, 0, qty);
        }
    }
}
