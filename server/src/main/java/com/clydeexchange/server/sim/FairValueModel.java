package com.clydeexchange.server.sim;

import java.util.Random;

/**
 * The "true" price the bots believe in. It moves as a random walk: each step it goes up or
 * down by a small random percentage. The bots quote and trade around it, so the traded price
 * follows it, which is what makes the chart look like a real market.
 */
public class FairValueModel {

    private static final long MIN_PRICE = 100;   // never let the walk reach zero or below

    private final Random random;
    private final double volatility;             // typical size of one step, as a fraction of price
    private long value;

    public FairValueModel(long startPrice, double volatility, Random random) {
        this.value = startPrice;
        this.volatility = volatility;
        this.random = random;
    }

    public long step() {
        long move = Math.round(random.nextGaussian() * value * volatility);
        value = Math.max(MIN_PRICE, value + move);
        return value;
    }

    public long value() {
        return value;
    }
}
