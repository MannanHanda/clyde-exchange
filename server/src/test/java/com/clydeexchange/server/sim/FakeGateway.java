package com.clydeexchange.server.sim;

import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;
import com.clydeexchange.server.service.ExchangeService;
import com.clydeexchange.server.service.OrderGateway;

import java.util.ArrayList;
import java.util.List;

/** Records what a bot asked for instead of sending it anywhere. */
class FakeGateway implements OrderGateway {

    record Placed(long id, String symbol, Side side, OrderType type, long price, long qty) {}

    final List<Placed> placed = new ArrayList<>();
    final List<Long> cancelled = new ArrayList<>();
    private long nextId = 1;

    @Override
    public ExchangeService.OrderResult placeOrder(String symbol, Side side, OrderType type, long price, long qty) {
        Placed p = new Placed(nextId++, symbol, side, type, price, qty);
        placed.add(p);
        return new ExchangeService.OrderResult(p.id(), List.of());
    }

    @Override
    public boolean cancelOrder(String symbol, long orderId) {
        cancelled.add(orderId);
        return true;
    }

    List<Placed> placed(Side side) {
        return placed.stream().filter(p -> p.side() == side).toList();
    }
}
