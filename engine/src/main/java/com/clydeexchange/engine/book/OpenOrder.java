package com.clydeexchange.engine.book;

import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;

/** A read-only picture of one order that is resting on a book, waiting to trade. */
public record OpenOrder(long orderId, String accountId, String symbol, Side side, OrderType type, long price, long stopPrice, long quantity, long remaining) {}
