package com.clydeexchange.server.service;

import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;

/**
 * What a trading bot is allowed to do: place and cancel orders for its own account. ExchangeService implements it;
 * bot tests use a fake, so the bots can be unit tested without Spring or a real engine.
 */
public interface OrderGateway {

    ExchangeService.OrderResult placeOrder(String accountId, String symbol, Side side, OrderType type, long price, long qty);

    boolean cancelOrder(String accountId, String symbol, long orderId);
}
