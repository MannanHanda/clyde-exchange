package com.clydeexchange.server.service;

import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;

/**
 * What a trading bot is allowed to do: place and cancel orders for its own account, and see what it has to trade with.
 * ExchangeService implements it; bot tests use a fake, so the bots can be unit tested without Spring or a real engine.
 */
public interface OrderGateway {

    /**
     * What an account can do in one symbol right now. buyingPower is cash not already promised to waiting buy orders;
     * held is the shares it has (negative if short); availableShares is held minus shares promised to waiting sell orders.
     */
    record Funds(long buyingPower, long held, long availableShares) {}

    ExchangeService.OrderResult placeOrder(String accountId, String symbol, Side side, OrderType type, long price, long qty);

    boolean cancelOrder(String accountId, String symbol, long orderId);

    Funds funds(String accountId, String symbol);
}
