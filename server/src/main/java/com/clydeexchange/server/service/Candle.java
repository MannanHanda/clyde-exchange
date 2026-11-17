package com.clydeexchange.server.service;

import java.util.ArrayList;
import java.util.List;

/**
 * A summary of all the trades in one stretch of time (a minute, an hour...): the first price (open),
 * the highest, the lowest, the last (close), and how many shares changed hands. It is what a
 * candlestick chart draws, and it lets us keep price history for ever without keeping every trade.
 *
 * start is the clock time, in milliseconds, at which the stretch begins.
 */
public record Candle(long start, long open, long high, long low, long close, long volume) {

    static Candle first(long start, long price, long qty) {
        return new Candle(start, price, price, price, price, qty);
    }

    /** This candle with one more trade added. */
    Candle plus(long price, long qty) {
        return new Candle(start, open, Math.max(high, price), Math.min(low, price), price, volume + qty);
    }

    /**
     * Builds bigger candles out of smaller ones: twelve 5-minute candles out of sixty 1-minute ones, say.
     *
     * @param candles oldest first
     */
    static List<Candle> merge(List<Candle> candles, long intervalMs) {
        List<Candle> merged = new ArrayList<>();
        Candle current = null;
        for (Candle c : candles) {
            long start = c.start() - Math.floorMod(c.start(), intervalMs);
            if (current != null && current.start() == start) {
                current = new Candle(start, current.open(), Math.max(current.high(), c.high()),
                        Math.min(current.low(), c.low()), c.close(), current.volume() + c.volume());
            } else {
                if (current != null) merged.add(current);
                current = new Candle(start, c.open(), c.high(), c.low(), c.close(), c.volume());
            }
        }
        if (current != null) merged.add(current);
        return merged;
    }
}
