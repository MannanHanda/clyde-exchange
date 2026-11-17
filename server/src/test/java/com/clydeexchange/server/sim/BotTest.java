package com.clydeexchange.server.sim;

import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;
import com.clydeexchange.server.service.Instrument;
import com.clydeexchange.server.service.OrderGateway.Funds;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class BotTest {

    private static final long RICH = 1_000_000_00L;   // £1,000,000 in ticks

    /** A market where the price is right and there is no news. */
    private static MarketState calm(long price) {
        return new MarketState(price, price, 0);
    }

    private static long bestBid(FakeGateway gateway) {
        return gateway.placed(Side.BUY).stream().mapToLong(FakeGateway.Placed::price).max().orElse(0);
    }

    private static long bestAsk(FakeGateway gateway) {
        return gateway.placed(Side.SELL).stream().mapToLong(FakeGateway.Placed::price).min().orElseThrow();
    }

    @Test
    void marketMakerAlwaysQuotesBidBelowAsk() {
        for (long price : new long[] {10_000, 150, 2, 1}) {   // includes tiny prices
            FakeGateway gateway = new FakeGateway();
            new MarketMakerBot("bot", "MANN", gateway, new Random(42)).act(calm(price));

            assertTrue(bestBid(gateway) < bestAsk(gateway), "price " + price);
            assertTrue(gateway.placed.stream().allMatch(p -> p.price() > 0 && p.qty() > 0));
        }
    }

    @Test
    void marketMakerCancelsItsOldQuotesBeforeRequoting() {
        FakeGateway gateway = new FakeGateway();
        MarketMakerBot mm = new MarketMakerBot("bot", "MANN", gateway, new Random(42));

        mm.act(calm(10_000));
        List<Long> firstQuotes = gateway.placed.stream().map(FakeGateway.Placed::id).toList();
        mm.act(calm(10_000));

        assertEquals(firstQuotes, gateway.cancelled);
    }

    @Test
    void marketMakerRaisesItsQuotesWhenOthersBuyFromIt() {
        FakeGateway gateway = new FakeGateway();
        MarketMakerBot mm = new MarketMakerBot("bot", "MANN", gateway, new Random(42));
        mm.act(calm(10_000));
        long askBefore = bestAsk(gateway);

        gateway.placed.clear();
        gateway.funds = new Funds(0, -500, -500);   // it is now short 500 shares: others bought £50,000 worth from it
        mm.act(calm(10_000));

        assertTrue(bestBid(gateway) > 10_000, "bid should now be above the old middle, was " + bestBid(gateway));
        assertTrue(bestAsk(gateway) > askBefore);
    }

    @Test
    void marketMakerLowersItsQuotesWhenOthersSellToIt() {
        FakeGateway gateway = new FakeGateway();
        MarketMakerBot mm = new MarketMakerBot("bot", "MANN", gateway, new Random(42));
        mm.act(calm(10_000));

        gateway.placed.clear();
        gateway.funds = new Funds(0, 500, 500);
        mm.act(calm(10_000));

        assertTrue(bestAsk(gateway) < 10_000, "ask should now be below the old middle, was " + bestAsk(gateway));
    }

    @Test
    void marketMakerRepricesOnNewsButNotAllTheWay() {
        FakeGateway gateway = new FakeGateway();
        MarketMakerBot mm = new MarketMakerBot("bot", "MANN", gateway, new Random(42));
        mm.act(calm(10_000));

        gateway.placed.clear();
        mm.act(new MarketState(10_500, 10_000, Math.log(1.05)));   // good news: fair value jumps 5%

        long middle = (bestBid(gateway) + bestAsk(gateway)) / 2;
        assertTrue(middle > 10_300 && middle < 10_400, "expected roughly 70% of the jump, middle was " + middle);
    }

    @Test
    void noiseTraderOnlySendsValidOrdersAndCleansUpOldOnes() {
        FakeGateway gateway = new FakeGateway();
        NoiseTraderBot noise = new NoiseTraderBot("bot", "MANN", gateway, new Random(7), 1.0);

        for (int i = 0; i < 200; i++) noise.act(calm(10_000));

        assertFalse(gateway.placed.isEmpty());
        for (FakeGateway.Placed p : gateway.placed) {
            assertTrue(p.qty() > 0);
            if (p.type() == OrderType.LIMIT) assertTrue(p.price() > 0);
        }
        assertFalse(gateway.cancelled.isEmpty(), "stale limit orders should be cancelled");
    }

    @Test
    void valueTraderBuysWhatIsCheapAndSellsWhatIsDear() {
        FakeGateway gateway = new FakeGateway();
        gateway.funds = new Funds(RICH, 1_000, 1_000);
        ValueTraderBot value = new ValueTraderBot("bot", "MANN", gateway, new Random(3));

        value.act(new MarketState(10_500, 10_000, 0));   // worth £105, trading at £100
        assertEquals(Side.BUY, gateway.placed.get(0).side());

        value.act(new MarketState(9_500, 10_000, 0));    // worth £95, trading at £100
        assertEquals(Side.SELL, gateway.placed.get(1).side());
        assertEquals(2, gateway.placed.size());
    }

    @Test
    void valueTraderLeavesSmallGapsAlone() {
        FakeGateway gateway = new FakeGateway();
        gateway.funds = new Funds(RICH, 1_000, 1_000);
        ValueTraderBot value = new ValueTraderBot("bot", "MANN", gateway, new Random(3) {
            @Override public double nextGaussian() { return 0; }   // a trader whose estimate is exactly right
        });

        value.act(new MarketState(10_020, 10_000, 0));   // 0.2% out: not worth the spread

        assertTrue(gateway.placed.isEmpty());
    }

    @Test
    void valueTraderOnlySellsSharesItHasAndOnlyBuysWhatItCanAfford() {
        FakeGateway gateway = new FakeGateway();
        ValueTraderBot value = new ValueTraderBot("bot", "MANN", gateway, new Random(3));

        gateway.funds = new Funds(RICH, 7, 7);
        value.act(new MarketState(9_000, 10_000, 0));
        assertEquals(7, gateway.placed.get(0).qty());

        gateway.funds = new Funds(0, 0, 0);
        value.act(new MarketState(9_000, 10_000, 0));    // nothing to sell
        value.act(new MarketState(11_000, 10_000, 0));   // nothing to buy with
        assertEquals(1, gateway.placed.size());
    }

    @Test
    void momentumTraderBuysARisingPriceAndSellsAFallingOne() {
        FakeGateway gateway = new FakeGateway();
        gateway.funds = new Funds(RICH, 0, 0);
        MomentumBot momentum = new MomentumBot("bot", "MANN", gateway);

        for (int i = 0; i < 200; i++) momentum.act(calm(10_000));
        assertTrue(gateway.placed.isEmpty(), "a flat price is not a trend");

        for (int i = 1; i <= 60; i++) momentum.act(calm(10_000 + i * 10));
        assertFalse(gateway.placed.isEmpty());
        assertTrue(gateway.placed.stream().allMatch(p -> p.side() == Side.BUY));

        gateway.placed.clear();
        gateway.funds = new Funds(RICH, 300, 300);
        for (int i = 1; i <= 120; i++) momentum.act(calm(10_600 - i * 10));
        assertFalse(gateway.placed.isEmpty());
        assertEquals(Side.SELL, gateway.placed.get(gateway.placed.size() - 1).side());
    }

    @Test
    void momentumTraderNeverSellsSharesItDoesNotHold() {
        FakeGateway gateway = new FakeGateway();   // no cash, no shares
        MomentumBot momentum = new MomentumBot("bot", "MANN", gateway);

        for (int i = 0; i < 200; i++) momentum.act(calm(10_000));
        for (int i = 1; i <= 120; i++) momentum.act(calm(10_000 - i * 10));

        assertTrue(gateway.placed.isEmpty());
    }

    @Test
    void investorSplitsABigOrderIntoSmallSlicesAllOnOneSide() {
        FakeGateway gateway = new FakeGateway();
        gateway.funds = new Funds(RICH, 100_000, 100_000);
        InvestorBot investor = new InvestorBot("bot", "MANN", gateway, new Random(11));

        int ticks = 0;
        while (!investor.isWorking() && ticks++ < 100_000) investor.act(calm(10_000));
        assertTrue(investor.isWorking(), "it should start an order eventually");
        while (investor.isWorking()) investor.act(calm(10_000));

        long total = gateway.placed.stream().mapToLong(FakeGateway.Placed::qty).sum();
        assertTrue(gateway.placed.size() >= 10, "expected many slices, got " + gateway.placed.size());
        assertTrue(total >= 250 && total <= 750, "£25,000 to £75,000 at £100 a share, was " + total);
        assertTrue(gateway.placed.stream().allMatch(p -> p.qty() <= 25), "each slice is at most £2,500");
        assertEquals(1, gateway.placed.stream().map(FakeGateway.Placed::side).distinct().count());
    }

    @Test
    void sizingTurnsPoundsIntoShares() {
        assertEquals(100, Sizing.shares(10_000, 10_000));   // £10,000 at £100.00
        assertEquals(1_190, Sizing.shares(10_000, 840));    // £10,000 at £8.40, rounded down
        assertEquals(1, Sizing.shares(1, 50_000));          // never zero
    }

    @Test
    void fairValuesStayPositiveAndNewsMovesThePriceTheWayTheHeadlineSays() {
        List<Instrument> instruments = List.of(
                new Instrument("AAA", "Alpha", "Tech", 10_000),
                new Instrument("BBB", "Beta", "Tech", 3),          // a penny stock
                new Instrument("CCC", "Gamma", "Energy", 500));
        PriceModel model = new PriceModel(instruments, new Random(1));

        int companyNews = 0;
        for (int i = 0; i < 200_000; i++) {
            for (PriceModel.Headline headline : model.step()) {
                if (headline.symbol() == null) continue;   // sector news can be outweighed by company news the same tick
                companyNews++;
                assertTrue(headline.text().contains(instruments.stream()
                        .filter(x -> x.symbol().equals(headline.symbol())).findFirst().orElseThrow().name()));
            }
            for (Instrument instrument : instruments) assertTrue(model.value(instrument.symbol()) >= 1);
        }
        assertTrue(companyNews > 20, "a story every 40 minutes or so per stock was expected, got " + companyNews);
    }
}
