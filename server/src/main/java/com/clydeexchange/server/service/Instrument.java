package com.clydeexchange.server.service;

/**
 * One thing you can trade. startPrice (in ticks) is where the simulator's price begins,
 * and what is shown before the first trade.
 */
public record Instrument(String symbol, String name, String sector, long startPrice) {}
