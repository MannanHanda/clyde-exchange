package com.clydeexchange.server.service;

/**
 * One row of the watchlist. open, high, low and volume are for the current trading day, which starts
 * at midnight UTC. previousClose is the last price of the day before (0 on the exchange's first day).
 * haltedUntil is a clock time in milliseconds (0 if the symbol has never been halted).
 */
public record MarketSummary(String symbol, String name, String sector, long last, long open, long high, long low,
                            long volume, long previousClose, boolean halted, long haltedUntil) {}
