package com.clydeexchange.server.sim;

import com.clydeexchange.server.account.AccountView;
import com.clydeexchange.server.service.CircuitBreaker;
import com.clydeexchange.server.service.ExchangeService;
import com.clydeexchange.server.service.Instrument;
import com.clydeexchange.server.service.MarketSummary;
import com.clydeexchange.server.store.TestDatabase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** The whole simulated market is stopped and started again from the database. */
class SimulatorRestartTest {

    private static final List<Instrument> INSTRUMENTS = List.of(
            new Instrument("MANN", "Mannan Technologies", "Technology", 10_000),
            new Instrument("TIDE", "Tidal Power", "Energy", 1_280));

    @Test
    void theMarketCarriesOnFromWhereItStopped() {
        TestDatabase database = new TestDatabase();
        AtomicLong now = new AtomicLong(1_800_000_000_000L);

        ExchangeService before = new ExchangeService(INSTRUMENTS, CircuitBreaker.Settings.OFF, now::get, database.store);
        Simulator simulator = new Simulator(before, new Random(7), headline -> {});
        for (int tick = 0; tick < 2_400; tick++) {   // ten minutes
            now.addAndGet(250);
            simulator.tick();
            if (tick % 4 == 0) database.store.save(before.drainChanges());   // once a second, like StateSaver
        }
        database.store.save(before.drainChanges());
        List<MarketSummary> marketBefore = before.market();
        AccountView valueBefore = before.account(Simulator.VALUE).orElseThrow();
        AccountView makerBefore = before.account(Simulator.MAKER).orElseThrow();
        assertTrue(before.openOrderCount() > 0);
        before.close();

        ExchangeService after = new ExchangeService(INSTRUMENTS, CircuitBreaker.Settings.OFF, now::get, database.store);
        assertEquals(0, after.openOrderCount(), "the bots' old orders are gone");
        Simulator restarted = new Simulator(after, new Random(8), headline -> {});

        // Same prices, and the bots have what they had: no second helping of starting shares
        assertEquals(marketBefore, after.market());
        AccountView valueAfter = after.account(Simulator.VALUE).orElseThrow();
        assertEquals(valueBefore.cash(), valueAfter.cash());
        assertEquals(valueBefore.positions(), valueAfter.positions());
        assertEquals(makerBefore.realisedPnl(), after.account(Simulator.MAKER).orElseThrow().realisedPnl());

        // Fair value starts at the last traded price, not back at the start price
        for (MarketSummary row : marketBefore) {
            assertEquals(row.last(), restarted.fairValue(row.symbol()), 1.0);
        }

        // And trading resumes
        for (int tick = 0; tick < 240; tick++) {
            now.addAndGet(250);
            restarted.tick();
        }
        for (int i = 0; i < INSTRUMENTS.size(); i++) {
            assertTrue(after.market().get(i).volume() > marketBefore.get(i).volume());
            assertFalse(after.book(INSTRUMENTS.get(i).symbol(), 1).bids().isEmpty());
        }
        after.close();
    }
}
