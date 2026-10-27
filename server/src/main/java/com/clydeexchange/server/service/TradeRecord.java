package com.clydeexchange.server.service;

/**
 * A trade as the outside world sees it. The engine's Trade has no symbol or clock time
 * (the engine is deterministic and never reads the clock), so the service adds both.
 */
public record TradeRecord(String symbol, long buyOrderId, long sellOrderId, long price, long qty, long timestamp) {}
