package com.clydeexchange.server.api;

import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;

/**
 * JSON body for POST /api/orders. accountId says whose order it is. Prices are in ticks.
 * price can be left out for MARKET and STOP orders; stopPrice is only needed for STOP and STOP_LIMIT.
 */
public record PlaceOrderRequest(String accountId, String symbol, Side side, OrderType type, Long price, Long stopPrice, Long qty) {}
