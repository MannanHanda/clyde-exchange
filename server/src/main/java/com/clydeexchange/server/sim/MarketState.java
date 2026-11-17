package com.clydeexchange.server.sim;

/**
 * What a bot is told about its symbol each tick.
 *
 * fairValue is the "true" worth from the PriceModel. Only informed bots should look at it: the whole
 * point of the simulation is that the traded price has to find it through buying and selling.
 * lastPrice is the last trade price, which everybody can see (the start price before the first trade).
 * newsJump is how far public news moved the fair value this tick, as a fraction (0.03 = up 3%; usually 0).
 */
public record MarketState(long fairValue, long lastPrice, double newsJump) {}
