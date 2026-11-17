package com.clydeexchange.server.sim;

import com.clydeexchange.server.service.ExchangeService;
import com.clydeexchange.server.service.Instrument;
import com.clydeexchange.server.service.MarketSummary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

/**
 * The simulated market: one PriceModel for all the stocks, and a cast of bots trading each one.
 * Call tick() and everything moves forward one step. Plain Java: Spring only supplies the clock
 * (see SimulatorRunner), so a test can run hours of market in a few seconds.
 *
 * Who is trading, in every symbol:
 *
 *   bot-maker        the market maker. Unlimited money, because it must always be able to quote.
 *   bot-noise-1..3   random small orders. Unlimited too, as there is no skill to limit.
 *   bot-value        knows roughly what the stock is worth and trades towards it.
 *   bot-momentum     buys what has been going up, sells what has been going down.
 *   bot-investor     now and then works a large order in small slices.
 *
 * The last three have real accounts, with a fixed pot of cash and shares shared across all symbols,
 * and go through the same risk checks as a human trader. You can look them up at /api/accounts/bot-value etc.
 */
public class Simulator {

    static final String MAKER = "bot-maker";
    static final List<String> NOISE = List.of("bot-noise-1", "bot-noise-2", "bot-noise-3");
    static final String VALUE = "bot-value";
    static final String MOMENTUM = "bot-momentum";
    static final String INVESTOR = "bot-investor";

    private final ExchangeService exchange;
    private final PriceModel model;
    private final Consumer<PriceModel.Headline> news;
    private final Map<String, List<Bot>> bots = new HashMap<>();
    private final Map<String, Double> unseenNews = new HashMap<>();   // news that broke while a symbol was halted

    public Simulator(ExchangeService exchange, Random random, Consumer<PriceModel.Headline> news) {
        this.exchange = exchange;
        this.news = news;
        this.model = new PriceModel(exchange.instruments(), random);

        exchange.openBotAccount(MAKER);
        NOISE.forEach(exchange::openBotAccount);
        exchange.openAccount(VALUE, pounds(2_000_000));
        exchange.openAccount(MOMENTUM, pounds(500_000));
        exchange.openAccount(INVESTOR, pounds(2_000_000));

        for (Instrument instrument : exchange.instruments()) {
            String symbol = instrument.symbol();
            long price = instrument.startPrice();
            exchange.seedShares(VALUE, symbol, Sizing.shares(100_000, price), price);
            exchange.seedShares(MOMENTUM, symbol, Sizing.shares(20_000, price), price);
            exchange.seedShares(INVESTOR, symbol, Sizing.shares(100_000, price), price);

            List<Bot> cast = new ArrayList<>();
            cast.add(new MarketMakerBot(MAKER, symbol, exchange, random));   // first, so there is always a book to trade against
            cast.add(new NoiseTraderBot(NOISE.get(0), symbol, exchange, random, 0.6));
            cast.add(new NoiseTraderBot(NOISE.get(1), symbol, exchange, random, 0.6));
            cast.add(new NoiseTraderBot(NOISE.get(2), symbol, exchange, random, 0.3));
            cast.add(new ValueTraderBot(VALUE, symbol, exchange, random));
            cast.add(new MomentumBot(MOMENTUM, symbol, exchange));
            cast.add(new InvestorBot(INVESTOR, symbol, exchange, random));
            bots.put(symbol, cast);
        }
    }

    public void tick() {
        model.step().forEach(news);

        for (MarketSummary row : exchange.market()) {
            String symbol = row.symbol();
            double newsJump = unseenNews.merge(symbol, model.newsJump(symbol), Double::sum);
            if (row.halted()) continue;   // nobody can trade; the news waits until they can
            unseenNews.put(symbol, 0.0);

            MarketState state = new MarketState(model.value(symbol), row.last(), newsJump);
            for (Bot bot : bots.get(symbol)) {
                try {
                    bot.act(state);
                } catch (IllegalArgumentException rejected) {
                    // The exchange said no (e.g. a halt began a moment ago). Bots just try again next tick.
                }
            }
        }
    }

    /** The fair value of a symbol, in ticks. For tests and tuning; the dashboard never shows it. */
    public long fairValue(String symbol) {
        return model.value(symbol);
    }

    private static long pounds(long pounds) {
        return pounds * 100;
    }
}
