package com.clydeexchange.server.sim;

import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;
import com.clydeexchange.server.service.ExchangeService;
import com.clydeexchange.server.service.OrderGateway;

import java.util.ArrayList;
import java.util.List;

/** Records what a bot asked for instead of sending it anywhere. Tests set funds to whatever the bot should think it has. */
class FakeGateway implements OrderGateway {

    record Placed(long id, String symbol, Side side, OrderType type, long price, long qty) {}

    final List<Placed> placed = new ArrayList<>();
    final List<Long> cancelled = new ArrayList<>();
    Funds funds = new Funds(0, 0, 0);
    private long nextId = 1;

    @Override
    public ExchangeService.OrderResult placeOrder(String accountId, String symbol, Side side, OrderType type, long price, long qty) {
        Placed p = new Placed(nextId++, symbol, side, type, price, qty);
        placed.add(p);
        return new ExchangeService.OrderResult(p.id(), List.of(), 0);
    }

    @Override
    public boolean cancelOrder(String accountId, String symbol, long orderId) {
        cancelled.add(orderId);
        return true;
    }

    @Override
    public Funds funds(String accountId, String symbol) {
        return funds;
    }

    List<Placed> placed(Side side) {
        return placed.stream().filter(p -> p.side() == side).toList();
    }
}
