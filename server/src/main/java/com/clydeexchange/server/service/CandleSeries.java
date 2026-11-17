package com.clydeexchange.server.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The candles of one symbol at one size (say, 1 minute), oldest first. Every trade goes into the
 * newest candle, or starts a new one when the clock has moved into the next minute.
 *
 * Only the most recent `capacity` candles are kept, so memory use is fixed however long the server runs.
 * Like the rest of the exchange's state, it is only touched by the writer thread.
 */
final class CandleSeries {

    private final long intervalMs;
    private final int capacity;
    private final boolean trackUnsaved;
    private final Deque<Candle> candles = new ArrayDeque<>();
    private final Map<Long, Candle> unsaved = new LinkedHashMap<>();   // by start time: candles changed since the last save

    /** @param trackUnsaved false when there is no database, so nothing piles up waiting to be saved */
    CandleSeries(long intervalMs, int capacity, boolean trackUnsaved) {
        this.intervalMs = intervalMs;
        this.capacity = capacity;
        this.trackUnsaved = trackUnsaved;
    }

    void add(long price, long qty, long now) {
        long start = now - Math.floorMod(now, intervalMs);
        Candle newest = candles.peekLast();
        Candle updated;
        if (newest == null || start > newest.start()) {
            updated = Candle.first(start, price, qty);
        } else {
            candles.removeLast();
            updated = newest.plus(price, qty);
        }
        candles.addLast(updated);
        if (candles.size() > capacity) candles.removeFirst();
        if (trackUnsaved) unsaved.put(updated.start(), updated);
    }

    /** Puts back a candle loaded from the database. They must arrive oldest first. */
    void restore(Candle candle) {
        candles.addLast(candle);
        if (candles.size() > capacity) candles.removeFirst();
    }

    List<Candle> all() {
        return List.copyOf(candles);
    }

    /** The candles that changed since this was last called. */
    List<Candle> drainUnsaved() {
        List<Candle> changed = new ArrayList<>(unsaved.values());
        unsaved.clear();
        return changed;
    }
}
