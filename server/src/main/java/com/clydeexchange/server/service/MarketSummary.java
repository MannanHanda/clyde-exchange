package com.clydeexchange.server.service;

/**
 * One row of the watchlist. open, high, low and volume count from when the server started.
 * haltedUntil is a clock time in milliseconds (0 if the symbol has never been halted).
 */
public record MarketSummary(String symbol, String name, String sector, long last, long open, long high, long low,
                            long volume, boolean halted, long haltedUntil) {}
