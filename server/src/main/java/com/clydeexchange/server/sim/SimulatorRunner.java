package com.clydeexchange.server.sim;

import com.clydeexchange.server.service.ExchangeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Spring's only job here is the clock: every 250 ms, move each symbol's fair value and let its bots act.
 * The bots are plain Java and go through ExchangeService like any other client.
 * Turned off with exchange.sim.enabled=false (the API tests do this so the book stays predictable).
 */
@Component
@ConditionalOnProperty(name = "exchange.sim.enabled", havingValue = "true")
public class SimulatorRunner {

    private static final Logger log = LoggerFactory.getLogger(SimulatorRunner.class);

    private record Market(FairValueModel fairValue, List<Bot> bots) {}

    private final List<Market> markets = new ArrayList<>();

    public SimulatorRunner(ExchangeService exchange, SimProperties props) {
        Random random = new Random();
        for (String symbol : exchange.symbols()) {
            long start = props.startPrices().getOrDefault(symbol, 10_000L);
            List<Bot> bots = List.of(
                    new MarketMakerBot(symbol, exchange, random),
                    new NoiseTraderBot(symbol, exchange, random, 0.6),
                    new NoiseTraderBot(symbol, exchange, random, 0.6),
                    new NoiseTraderBot(symbol, exchange, random, 0.3));
            markets.add(new Market(new FairValueModel(start, 0.001, random), bots));
        }
    }

    @Scheduled(fixedRate = 250)
    public void tick() {
        try {
            for (Market market : markets) {
                long fair = market.fairValue().step();
                for (Bot bot : market.bots()) bot.act(fair);
            }
        } catch (RuntimeException e) {
            log.warn("Simulator tick failed", e);   // keep the market running if one tick goes wrong
        }
    }
}
