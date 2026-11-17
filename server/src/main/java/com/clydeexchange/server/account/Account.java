package com.clydeexchange.server.account;

import com.clydeexchange.engine.model.Side;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One participant's money and shares. Every trade moves cash one way and shares the other:
 * the buyer pays price x qty and receives the shares, the seller the reverse.
 *
 * Not thread-safe on purpose: like the engine, it is only ever touched by the exchange's writer thread.
 * All money is in ticks (pence), as everywhere else.
 */
public class Account {

    /** One execution against this account, as shown in its trade history. */
    public record Fill(long orderId, String symbol, Side side, long price, long qty, long timestamp) {}

    private static final int FILL_HISTORY = 100;

    private final String id;
    private final boolean unlimited;
    private long cash;
    private long realisedPnl;
    private final Map<String, Position> positions = new LinkedHashMap<>();
    private final Deque<Fill> fills = new ArrayDeque<>();

    /**
     * @param unlimited true for the simulator's bots: they skip the buying-power checks (so they can
     *                  go short and overdrawn), but their cash, positions and profit are still tracked
     */
    public Account(String id, long startingCash, boolean unlimited) {
        this.id = id;
        this.cash = startingCash;
        this.unlimited = unlimited;
    }

    public void applyFill(Fill fill) {
        long value = Math.multiplyExact(fill.price(), fill.qty());
        long signedQty = (fill.side() == Side.BUY) ? fill.qty() : -fill.qty();
        cash += (fill.side() == Side.BUY) ? -value : value;
        realisedPnl += positions.computeIfAbsent(fill.symbol(), s -> new Position()).apply(signedQty, fill.price());

        fills.addFirst(fill);
        if (fills.size() > FILL_HISTORY) fills.removeLast();
    }

    /** Adds shares without paying for them, valued at costPerShare. Used to give the simulator's bots their starting holdings. */
    public void depositShares(String symbol, long qty, long costPerShare) {
        positions.computeIfAbsent(symbol, s -> new Position()).apply(qty, costPerShare);
    }

    public String id() { return id; }
    public boolean unlimited() { return unlimited; }
    public long cash() { return cash; }
    public long realisedPnl() { return realisedPnl; }

    /** Shares held in this symbol: negative means short, 0 if never traded. */
    public long held(String symbol) {
        Position position = positions.get(symbol);
        return (position == null) ? 0 : position.qty();
    }

    public Map<String, Position> positions() {
        return Collections.unmodifiableMap(positions);
    }

    /** Newest first. */
    public List<Fill> fills() {
        return List.copyOf(fills);
    }
}
