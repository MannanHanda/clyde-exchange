package com.clydeexchange.server.service;

import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;

/**
 * What a trading bot is allowed to do: place and cancel orders. ExchangeService implements it;
 * bot tests use a fake, so the bots can be unit tested without Spring or a real engine.
 */
public interface OrderGateway {

    ExchangeService.OrderResult placeOrder(String symbol, Side side, OrderType type, long price, long qty);

    boolean cancelOrder(String symbol, long orderId);
}
