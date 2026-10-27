package com.clydeexchange.engine.book;

import java.util.List;

/** A read-only picture of the top of one book: best prices first on both sides. */
public record BookSnapshot(String symbol, List<Level> bids, List<Level> asks) {

    public record Level(long price, long qty) {}
}
