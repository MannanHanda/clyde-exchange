package com.clydeexchange.server.sim;

import com.clydeexchange.server.service.Instrument;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The "true" worth of every stock: what each one would trade at if everybody knew everything.
 * Nobody sees it directly except the informed bots; the traded price has to chase it.
 *
 * Each tick, a stock's fair value moves by the sum of several things real prices are known to do:
 *
 *   - the whole market moves a little (so all stocks tend to rise and fall together),
 *   - its sector moves a little (so two energy stocks move together more than an energy and a bank),
 *   - the stock moves a little on its own,
 *   - a trend: a small steady push up or down that lasts a few minutes, then changes,
 *   - now and then, news: a sudden jump, with a headline,
 *   - a very weak pull back towards where it started, so prices can wander for hours but not forever.
 *
 * The random moves are bigger during a "storm", which starts and ends at random. Markets really do
 * alternate between calm and nervous spells like this.
 *
 * All moves are percentages (strictly, logs), so a price can never reach zero. Probabilities are per
 * tick; at 4 ticks a second, 1/2400 means "about every 10 minutes".
 */
public class PriceModel {

    /** A piece of news. symbol is null when it is about a whole sector. */
    public record Headline(String symbol, String sector, String text, boolean good) {}

    private static final double MARKET_MOVE = 0.00006;   // typical size of each random move, as a fraction of price
    private static final double SECTOR_MOVE = 0.00008;
    private static final double OWN_MOVE = 0.00011;

    private static final double STORM_STARTS = 1.0 / 2400;
    private static final double STORM_ENDS = 1.0 / 720;
    private static final double STORM_MULTIPLIER = 2.5;

    private static final double TREND_CHANGES = 1.0 / 1200;
    private static final double TREND_SIZE = 0.000005;

    private static final double PULL_TO_START = 0.000004;

    private static final double COMPANY_NEWS = 1.0 / 9600;   // per stock: about every 40 minutes
    private static final double MAJOR_NEWS_SHARE = 0.15;     // of company news; big enough to trip the circuit breaker
    private static final double SECTOR_NEWS = 1.0 / 28800;   // per sector: about every 2 hours

    private static final List<String> GOOD_NEWS = List.of(
            "%s beats profit forecasts", "%s wins a major contract", "Analysts upgrade %s",
            "%s raises its full-year guidance", "%s announces a share buyback");
    private static final List<String> BAD_NEWS = List.of(
            "%s issues a profit warning", "Regulator opens an inquiry into %s", "Analysts downgrade %s",
            "%s loses a key customer", "%s cuts its full-year guidance");
    private static final List<String> MAJOR_GOOD_NEWS = List.of(
            "%s receives a takeover approach", "%s reports record results, far ahead of forecasts");
    private static final List<String> MAJOR_BAD_NEWS = List.of(
            "%s chief executive resigns unexpectedly", "%s uncovers an accounting error");

    private static final class Stock {
        final Instrument instrument;
        double move;       // log of (fair value / start price): 0 at the start, 0.1 is about 10% up
        double trend;
        double newsJump;   // this tick only

        Stock(Instrument instrument) {
            this.instrument = instrument;
        }
    }

    private final Random random;
    private final Map<String, Stock> stocks = new LinkedHashMap<>();
    private final List<String> sectors;
    private boolean stormy;

    public PriceModel(List<Instrument> instruments, Random random) {
        this.random = random;
        for (Instrument instrument : instruments) stocks.put(instrument.symbol(), new Stock(instrument));
        this.sectors = instruments.stream().map(Instrument::sector).distinct().toList();
    }

    /** Moves every stock forward one tick. Returns the news that broke this tick (usually none). */
    public List<Headline> step() {
        List<Headline> headlines = new ArrayList<>();
        if (random.nextDouble() < (stormy ? STORM_ENDS : STORM_STARTS)) stormy = !stormy;
        double scale = stormy ? STORM_MULTIPLIER : 1;

        double marketMove = random.nextGaussian() * MARKET_MOVE * scale;
        Map<String, Double> sectorMoves = new HashMap<>();
        Map<String, Double> sectorJumps = new HashMap<>();
        for (String sector : sectors) {
            sectorMoves.put(sector, random.nextGaussian() * SECTOR_MOVE * scale);
            double jump = 0;
            if (random.nextDouble() < SECTOR_NEWS) {
                jump = randomSign() * between(0.01, 0.03);
                String text = (jump > 0) ? "Strong demand lifts %s shares" : "New regulation weighs on %s shares";
                headlines.add(new Headline(null, sector, text.formatted(sector), jump > 0));
            }
            sectorJumps.put(sector, jump);
        }

        for (Stock stock : stocks.values()) {
            String sector = stock.instrument.sector();
            if (random.nextDouble() < TREND_CHANGES) stock.trend = random.nextGaussian() * TREND_SIZE;

            stock.newsJump = sectorJumps.get(sector);
            if (random.nextDouble() < COMPANY_NEWS) {
                boolean major = random.nextDouble() < MAJOR_NEWS_SHARE;
                double jump = randomSign() * (major ? between(0.05, 0.09) : between(0.01, 0.04));
                stock.newsJump += jump;
                List<String> texts = (jump > 0) ? (major ? MAJOR_GOOD_NEWS : GOOD_NEWS) : (major ? MAJOR_BAD_NEWS : BAD_NEWS);
                String text = texts.get(random.nextInt(texts.size())).formatted(stock.instrument.name());
                headlines.add(new Headline(stock.instrument.symbol(), sector, text, jump > 0));
            }

            stock.move += stock.trend + marketMove + sectorMoves.get(sector)
                    + random.nextGaussian() * OWN_MOVE * scale
                    - PULL_TO_START * stock.move
                    + stock.newsJump;
        }
        return headlines;
    }

    /** The fair value in ticks. Never below 1. */
    public long value(String symbol) {
        Stock stock = stocks.get(symbol);
        return Math.max(1, Math.round(stock.instrument.startPrice() * Math.exp(stock.move)));
    }

    /** How far news moved this stock in the latest tick, as a fraction: 0.03 is up 3%. Usually 0. */
    public double newsJump(String symbol) {
        return stocks.get(symbol).newsJump;
    }

    public boolean stormy() {
        return stormy;
    }

    private double randomSign() {
        return random.nextBoolean() ? 1 : -1;
    }

    private double between(double low, double high) {
        return low + random.nextDouble() * (high - low);
    }
}
