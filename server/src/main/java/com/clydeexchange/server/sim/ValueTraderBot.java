package com.clydeexchange.server.sim;

import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;
import com.clydeexchange.server.service.OrderGateway;

import java.util.Random;

/**
 * The informed trader: it has done its research and has an estimate of what the stock is really worth.
 * When the price is clearly below that it buys, and when clearly above it sells, harder the bigger the gap.
 * Its trading is what drags the price towards fair value ("price discovery").
 *
 * It is not perfect: each one has its own small, fixed error in its estimate. And it has limited money:
 * it can only buy with cash it has and only sell shares it holds, so it cannot correct every mispricing.
 */
public class ValueTraderBot implements Bot {

    private static final double MIN_GAP = 0.003;              // ignore gaps under 0.3%: not worth paying the spread
    private static final long POUNDS_PER_PERCENT = 3_000;     // each tick, trade £3,000 for every 1% of gap...
    private static final long MAX_POUNDS_PER_TICK = 10_000;   // ...up to this much
    private static final long MAX_POSITION_POUNDS = 300_000;  // never hold more than this in one stock

    private final String accountId;
    private final String symbol;
    private final OrderGateway exchange;
    private final double estimateError;   // e.g. 0.002 means it always thinks the stock is worth 0.2% more than it is

    public ValueTraderBot(String accountId, String symbol, OrderGateway exchange, Random random) {
        this.accountId = accountId;
        this.symbol = symbol;
        this.exchange = exchange;
        this.estimateError = random.nextGaussian() * 0.002;
    }

    @Override
    public void act(MarketState market) {
        long last = market.lastPrice();
        double estimate = market.fairValue() * (1 + estimateError);
        double gap = estimate / last - 1;   // positive: the stock looks cheap
        if (Math.abs(gap) < MIN_GAP) return;

        long pounds = Math.min(MAX_POUNDS_PER_TICK, Math.round(Math.abs(gap) * 100 * POUNDS_PER_PERCENT));
        long qty = Sizing.shares(pounds, last);
        OrderGateway.Funds funds = exchange.funds(accountId, symbol);

        if (gap > 0) {
            long roomInPosition = Sizing.shares(MAX_POSITION_POUNDS, last) - funds.held();
            long affordable = funds.buyingPower() / (last + last / 50 + 1);   // leave 2% in case the price runs away
            qty = Math.min(qty, Math.min(roomInPosition, affordable));
            if (qty > 0) exchange.placeOrder(accountId, symbol, Side.BUY, OrderType.MARKET, 0, qty);
        } else {
            qty = Math.min(qty, funds.availableShares());
            if (qty > 0) exchange.placeOrder(accountId, symbol, Side.SELL, OrderType.MARKET, 0, qty);
        }
    }
}
