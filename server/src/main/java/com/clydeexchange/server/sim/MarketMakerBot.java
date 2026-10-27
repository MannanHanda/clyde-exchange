package com.clydeexchange.server.sim;

import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;
import com.clydeexchange.server.service.OrderGateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Provides liquidity: always has buy orders just below fair value and sell orders just above it,
 * a few levels deep. Every tick it cancels its old quotes and re-quotes around the new fair value.
 * It earns the spread when others trade against it; that is how real market makers make money.
 */
public class MarketMakerBot implements Bot {

    private static final int LEVELS = 3;

    private final String symbol;
    private final OrderGateway exchange;
    private final Random random;
    private final List<Long> liveOrderIds = new ArrayList<>();

    public MarketMakerBot(String symbol, OrderGateway exchange, Random random) {
        this.symbol = symbol;
        this.exchange = exchange;
        this.random = random;
    }

    @Override
    public void act(long fairValue) {
        for (long id : liveOrderIds) exchange.cancelOrder(symbol, id);   // false if already filled: fine
        liveOrderIds.clear();

        long halfSpread = Math.max(1, fairValue / 2000);   // 0.05% each side, at least 1 tick
        long levelGap = Math.max(1, fairValue / 1000);
        for (int i = 0; i < LEVELS; i++) {
            long bid = fairValue - halfSpread - i * levelGap;
            long ask = fairValue + halfSpread + i * levelGap;
            if (bid > 0) quote(Side.BUY, bid);
            quote(Side.SELL, ask);
        }
    }

    private void quote(Side side, long price) {
        long qty = 50 + random.nextInt(150);
        liveOrderIds.add(exchange.placeOrder(symbol, side, OrderType.LIMIT, price, qty).orderId());
    }
}
