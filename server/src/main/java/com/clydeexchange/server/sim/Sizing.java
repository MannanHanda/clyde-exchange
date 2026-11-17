package com.clydeexchange.server.sim;

/**
 * Bots think in pounds, not shares, so that a £12 stock and a £180 stock get the same amount of
 * money traded in them. This turns an amount of money into a number of shares.
 */
final class Sizing {

    private Sizing() {}

    /** How many whole shares this many pounds buys at this price (in ticks). At least 1. */
    static long shares(long pounds, long price) {
        return Math.max(1, pounds * 100 / Math.max(1, price));
    }
}
