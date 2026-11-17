package com.clydeexchange.server.sim;

import com.clydeexchange.server.news.NewsFeed;
import com.clydeexchange.server.service.ExchangeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * Spring's only job here is the clock: every 250 ms, move the Simulator forward one tick,
 * and pass its headlines on to the news feed.
 * Turned off with exchange.sim.enabled=false (the API tests do this so the book stays predictable).
 */
@Component
@ConditionalOnProperty(name = "exchange.sim.enabled", havingValue = "true")
public class SimulatorRunner {

    private static final Logger log = LoggerFactory.getLogger(SimulatorRunner.class);

    private final Simulator simulator;

    public SimulatorRunner(ExchangeService exchange, NewsFeed newsFeed) {
        this.simulator = new Simulator(exchange, new Random(), headline -> newsFeed.publish(
                headline.symbol(), headline.sector(), headline.text(),
                headline.good() ? NewsFeed.Kind.GOOD : NewsFeed.Kind.BAD));
    }

    @Scheduled(fixedRate = 250)
    public void tick() {
        try {
            simulator.tick();
        } catch (RuntimeException e) {
            log.warn("Simulator tick failed", e);   // keep the market running if one tick goes wrong
        }
    }
}
