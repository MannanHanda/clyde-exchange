package com.clydeexchange.server.service;

/**
 * Pauses trading in one symbol when its price moves too far, too fast. Real exchanges do this
 * (a "trading halt") to give everyone time to think instead of panicking.
 *
 * It remembers a reference price, refreshed every window. A trade that is thresholdPercent or more
 * away from the reference starts a halt. While halted, new orders are rejected; cancels still work.
 * When the halt ends, the price it stopped at becomes the new reference.
 *
 * Plain Java with the time passed in, so it can be tested without waiting.
 */
public class CircuitBreaker {

    /** thresholdPercent of 0 (or less) switches the breaker off. */
    public record Settings(double thresholdPercent, int windowSeconds, int haltSeconds) {

        public static final Settings OFF = new Settings(0, 0, 0);
    }

    private final Settings settings;
    private long referencePrice;
    private long referenceTime;
    private long haltedUntil;

    public CircuitBreaker(Settings settings) {
        this.settings = settings;
    }

    /**
     * Call for every trade.
     * @return how far the price has moved from the reference, in percent, if this trade starts a halt; otherwise 0
     */
    public double onTrade(long price, long now) {
        if (settings.thresholdPercent() <= 0) return 0;

        boolean stale = now - referenceTime >= settings.windowSeconds() * 1000L;
        if (referencePrice == 0 || stale) {
            referencePrice = price;
            referenceTime = now;
            return 0;
        }

        double movePercent = 100.0 * (price - referencePrice) / referencePrice;
        if (Math.abs(movePercent) < settings.thresholdPercent()) return 0;

        haltedUntil = now + settings.haltSeconds() * 1000L;
        referencePrice = price;
        referenceTime = haltedUntil;   // the window starts again when trading does
        return movePercent;
    }

    public boolean isHalted(long now) {
        return now < haltedUntil;
    }

    public long haltedUntil() {
        return haltedUntil;
    }
}
