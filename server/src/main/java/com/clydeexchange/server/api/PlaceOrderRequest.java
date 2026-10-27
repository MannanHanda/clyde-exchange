package com.clydeexchange.server.api;

import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;

/** JSON body for POST /api/orders. Price is in ticks and can be left out for MARKET orders. */
public record PlaceOrderRequest(String symbol, Side side, OrderType type, Long price, Long qty) {}
