package com.clydeexchange.server.api;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Allows each key (an account) a fixed number of calls in any one clock second.
 * It exists so that one runaway bot cannot fill the exchange's single writer thread and slow everyone else down.
 */
public class RateLimiter {

    private record Window(long second, int count) {}

    private final int perSecond;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter(int perSecond) {
        this.perSecond = perSecond;
    }

    /** Counts this call, and says whether it is still within the limit. */
    public boolean allow(String key, long nowMillis) {
        long second = nowMillis / 1000;
        Window window = windows.merge(key, new Window(second, 1), (old, first) ->
                (old.second() == second) ? new Window(second, old.count() + 1) : first);
        return window.count() <= perSecond;
    }
}
