package com.clydeexchange.server.service;

import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Candles, trading days and the cap on open orders. */
class CandleTest {

    private static final long MINUTE = 60_000;
    private static final long DAY = 24 * 60 * MINUTE;

    private final AtomicLong now = new AtomicLong(20_000 * DAY);   // midnight UTC, some day in 2024
    private final ExchangeService exchange = new ExchangeService(
            List.of(new Instrument("MANN", "Mannan Technologies", "Technology", 10_000)),
            CircuitBreaker.Settings.OFF, now::get);

    private void tradeAt(long price, long qty) {
        exchange.openBotAccount("maker");
        exchange.openBotAccount("taker");
        exchange.placeOrder("maker", "MANN", Side.SELL, OrderType.LIMIT, price, qty);
        exchange.placeOrder("taker", "MANN", Side.BUY, OrderType.LIMIT, price, qty);
    }

    @Test
    void tradesInTheSameMinuteShareACandle() {
        tradeAt(100, 5);
        now.addAndGet(20_000);
        tradeAt(104, 1);
        now.addAndGet(20_000);
        tradeAt(98, 2);
        now.addAndGet(20_000);   // the next minute
        tradeAt(101, 4);

        List<Candle> candles = exchange.candles("MANN", "1m", 100);
        assertEquals(List.of(
                new Candle(20_000 * DAY, 100, 104, 98, 98, 8),
                new Candle(20_000 * DAY + MINUTE, 101, 101, 101, 101, 4)), candles);
        assertEquals(List.of(candles.get(1)), exchange.candles("MANN", "1m", 1), "limit keeps the newest");
        exchange.close();
    }

    @Test
    void biggerCandlesAreBuiltFromSmallerOnes() {
        long[] prices = {100, 103, 99, 101, 102, 107, 105};   // one trade a minute for seven minutes
        for (long price : prices) {
            tradeAt(price, 1);
            now.addAndGet(MINUTE);
        }

        assertEquals(7, exchange.candles("MANN", "1m", 100).size());
        assertEquals(List.of(
                new Candle(20_000 * DAY, 100, 103, 99, 102, 5),
                new Candle(20_000 * DAY + 5 * MINUTE, 107, 107, 105, 105, 2)), exchange.candles("MANN", "5m", 100));
        assertEquals(List.of(new Candle(20_000 * DAY, 100, 107, 99, 105, 7)), exchange.candles("MANN", "1d", 100));
        assertEquals(exchange.candles("MANN", "1h", 100), exchange.candles("MANN", "4h", 100));

        assertThrows(IllegalArgumentException.class, () -> exchange.candles("MANN", "7m", 100));
        assertThrows(IllegalArgumentException.class, () -> exchange.candles("NOPE", "1m", 100));
        exchange.close();
    }

    @Test
    void onlyTheNewestCandlesAreKept() {
        CandleSeries series = new CandleSeries(MINUTE, 3, true);
        for (int minute = 0; minute < 10; minute++) series.add(100 + minute, 1, minute * MINUTE);

        assertEquals(3, series.all().size());
        assertEquals(107, series.all().get(0).open());
        assertEquals(10, series.drainUnsaved().size(), "all ten still have to be saved");
        assertTrue(series.drainUnsaved().isEmpty());
    }

    @Test
    void aNewDayStartsAtMidnightAndRemembersYesterdaysClose() {
        now.addAndGet(23 * 60 * MINUTE);   // 23:00
        tradeAt(100, 5);
        tradeAt(120, 5);
        MarketSummary today = exchange.market().get(0);
        assertEquals(100, today.open());
        assertEquals(10, today.volume());
        assertEquals(0, today.previousClose(), "there was no yesterday");

        now.addAndGet(61 * MINUTE);   // 00:01 the next day
        MarketSummary quiet = exchange.market().get(0);
        assertEquals(120, quiet.last());
        assertEquals(120, quiet.previousClose());
        assertEquals(0, quiet.open(), "nothing has traded yet today");
        assertEquals(0, quiet.volume());

        tradeAt(118, 2);
        MarketSummary tomorrow = exchange.market().get(0);
        assertEquals(118, tomorrow.open());
        assertEquals(118, tomorrow.high());
        assertEquals(2, tomorrow.volume());
        assertEquals(120, tomorrow.previousClose());
        exchange.close();
    }

    @Test
    void aTraderCannotPileUpOrdersWithoutLimit() {
        exchange.openAccount("alice", 1_000_000);
        for (int i = 0; i < 200; i++) exchange.placeOrder("alice", "MANN", Side.BUY, OrderType.LIMIT, 1, 1);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> exchange.placeOrder("alice", "MANN", Side.BUY, OrderType.LIMIT, 1, 1));
        assertEquals("Too many open orders (200): cancel some first", error.getMessage());
        assertEquals(200, exchange.openOrderCount());
        exchange.close();
    }
}
