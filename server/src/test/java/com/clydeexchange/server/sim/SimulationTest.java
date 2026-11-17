package com.clydeexchange.server.sim;

import com.clydeexchange.engine.book.BookSnapshot;
import com.clydeexchange.server.account.AccountView;
import com.clydeexchange.server.service.CircuitBreaker;
import com.clydeexchange.server.service.ExchangeService;
import com.clydeexchange.server.service.Instrument;
import com.clydeexchange.server.service.MarketSummary;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the whole simulated market (real exchange, real bots, no Spring) for an hour of market time
 * in a few seconds, with a fixed random seed and a fake clock, and checks it stays healthy.
 */
class SimulationTest {

    private static final List<Instrument> INSTRUMENTS = List.of(
            new Instrument("MANN", "Mannan Technologies", "Technology", 10_000),
            new Instrument("KELV", "Kelvin Systems", "Technology", 18_200),
            new Instrument("TIDE", "Tidal Power", "Energy", 1_280),
            new Instrument("LOMD", "Lomond Foods", "Consumer", 840));

    private static final int TICKS = 14_400;   // one hour at 4 ticks a second

    @Test
    void anHourOfTradingStaysHealthy() {
        AtomicLong now = new AtomicLong(1_000_000);
        AtomicInteger halts = new AtomicInteger();
        List<PriceModel.Headline> headlines = new ArrayList<>();
        ExchangeService exchange = new ExchangeService(INSTRUMENTS, new CircuitBreaker.Settings(5, 60, 15), now::get);
        exchange.addHaltListener(halt -> halts.incrementAndGet());
        Simulator simulator = new Simulator(exchange, new Random(2026), headlines::add);

        Map<String, Double> gapSum = new HashMap<>();
        Map<String, Double> gapMax = new HashMap<>();
        Map<String, Double> spreadSum = new HashMap<>();
        int samples = 0;

        for (int tick = 1; tick <= TICKS; tick++) {
            now.addAndGet(250);
            simulator.tick();
            if (tick % 20 != 0 || tick < 240) continue;   // sample every 5 seconds, after a minute to settle

            samples++;
            for (MarketSummary row : exchange.market()) {
                String symbol = row.symbol();
                BookSnapshot book = exchange.book(symbol, 1);
                assertFalse(book.bids().isEmpty(), symbol + " has no bids at tick " + tick);
                assertFalse(book.asks().isEmpty(), symbol + " has no asks at tick " + tick);
                long bid = book.bids().get(0).price();
                long ask = book.asks().get(0).price();
                assertTrue(bid < ask, symbol + " book is crossed at tick " + tick);

                double gap = Math.abs((double) row.last() / simulator.fairValue(symbol) - 1);
                gapSum.merge(symbol, gap, Double::sum);
                gapMax.merge(symbol, gap, Math::max);
                spreadSum.merge(symbol, (double) (ask - bid) / bid, Double::sum);
            }
        }

        for (MarketSummary row : exchange.market()) {
            String symbol = row.symbol();
            double averageGap = gapSum.get(symbol) / samples;
            double averageSpread = spreadSum.get(symbol) / samples;
            System.out.printf("%s last %d fair %d  low %d high %d  volume %d  avg gap %.2f%% max gap %.2f%%  avg spread %.3f%%%n",
                    symbol, row.last(), simulator.fairValue(symbol), row.low(), row.high(), row.volume(),
                    averageGap * 100, gapMax.get(symbol) * 100, averageSpread * 100);
            assertTrue(row.volume() > 0, symbol + " never traded");
            assertTrue(averageGap < 0.02, symbol + " drifted from fair value: average gap " + averageGap);
            assertTrue(averageSpread < 0.01, symbol + " spread too wide: " + averageSpread);
        }
        System.out.printf("%d headlines, %d halts%n", headlines.size(), halts.get());

        // The bots with limited money must never have spent cash or sold shares they did not have
        for (String bot : List.of(Simulator.VALUE, Simulator.MOMENTUM, Simulator.INVESTOR)) {
            AccountView account = exchange.account(bot).orElseThrow();
            System.out.printf("%s cash %d equity %d realised %d unrealised %d%n", bot, account.cash() / 100,
                    account.equity() / 100, account.realisedPnl() / 100, account.unrealisedPnl() / 100);
            assertTrue(account.cash() >= 0, bot + " is overdrawn");
            assertTrue(account.positions().stream().allMatch(p -> p.qty() >= 0), bot + " is short");
        }
        for (String bot : List.of(Simulator.MAKER, "bot-noise-1")) {
            AccountView account = exchange.account(bot).orElseThrow();
            System.out.printf("%s equity %d realised %d unrealised %d%n", bot,
                    account.equity() / 100, account.realisedPnl() / 100, account.unrealisedPnl() / 100);
        }
        exchange.close();
    }
}
