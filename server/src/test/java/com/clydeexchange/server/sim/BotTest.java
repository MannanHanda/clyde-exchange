package com.clydeexchange.server.sim;

import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class BotTest {

    @Test
    void marketMakerAlwaysQuotesBidBelowAsk() {
        FakeGateway gateway = new FakeGateway();
        MarketMakerBot mm = new MarketMakerBot("MANN", gateway, new Random(42));

        for (long fair : new long[] {10_000, 150, 2, 1}) {   // includes tiny prices
            gateway.placed.clear();
            mm.act(fair);
            long bestBid = gateway.placed(Side.BUY).stream().mapToLong(FakeGateway.Placed::price).max().orElse(0);
            long bestAsk = gateway.placed(Side.SELL).stream().mapToLong(FakeGateway.Placed::price).min().orElseThrow();
            assertTrue(bestBid < bestAsk, "fair " + fair + ": bid " + bestBid + " >= ask " + bestAsk);
            assertTrue(gateway.placed.stream().allMatch(p -> p.price() > 0 && p.qty() > 0));
        }
    }

    @Test
    void marketMakerCancelsItsOldQuotesBeforeRequoting() {
        FakeGateway gateway = new FakeGateway();
        MarketMakerBot mm = new MarketMakerBot("MANN", gateway, new Random(42));

        mm.act(10_000);
        List<Long> firstQuotes = gateway.placed.stream().map(FakeGateway.Placed::id).toList();
        mm.act(10_010);

        assertEquals(firstQuotes, gateway.cancelled);
    }

    @Test
    void noiseTraderOnlySendsValidOrdersAndCleansUpOldOnes() {
        FakeGateway gateway = new FakeGateway();
        NoiseTraderBot noise = new NoiseTraderBot("MANN", gateway, new Random(7), 1.0);

        for (int i = 0; i < 200; i++) noise.act(10_000);

        assertFalse(gateway.placed.isEmpty());
        for (FakeGateway.Placed p : gateway.placed) {
            assertTrue(p.qty() > 0);
            if (p.type() == OrderType.LIMIT) assertTrue(p.price() > 0);
        }
        assertFalse(gateway.cancelled.isEmpty(), "stale limit orders should be cancelled");
    }

    @Test
    void fairValueNeverGoesBelowTheFloor() {
        FairValueModel model = new FairValueModel(150, 0.5, new Random(1));   // huge volatility
        for (int i = 0; i < 10_000; i++) assertTrue(model.step() >= 100);
    }
}
