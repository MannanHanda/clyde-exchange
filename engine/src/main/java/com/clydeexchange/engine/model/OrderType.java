package com.clydeexchange.engine.model;

public enum OrderType {
    LIMIT,
    MARKET,
    STOP,        // waits off the book; becomes a MARKET order once the price reaches its stop price
    STOP_LIMIT   // the same, but becomes a LIMIT order
}
