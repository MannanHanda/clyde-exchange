package com.clydeexchange.server.store;

import com.clydeexchange.engine.book.OpenOrder;
import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;
import com.clydeexchange.server.account.AccountView;
import com.clydeexchange.server.news.NewsFeed;
import com.clydeexchange.server.service.Candle;
import com.clydeexchange.server.service.CircuitBreaker;
import com.clydeexchange.server.service.ExchangeService;
import com.clydeexchange.server.service.ExchangeStore;
import com.clydeexchange.server.service.Instrument;
import com.clydeexchange.server.service.MarketSummary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "Restarts" the exchange: runs one, saves it to a real database, throws it away, builds a new one
 * from the same database and checks that nothing a trader cares about has changed.
 */
class RestartTest {

    private static final List<Instrument> INSTRUMENTS = List.of(
            new Instrument("MANN", "Mannan Technologies", "Technology", 10_000),
            new Instrument("CLYD", "Clyde Digital", "Technology", 5_000));

    private final TestDatabase database = new TestDatabase();
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);   // some time in 2027

    private ExchangeService start() {
        return new ExchangeService(INSTRUMENTS, CircuitBreaker.Settings.OFF, now::get, database.store);
    }

    private void saveAndStop(ExchangeService exchange) {
        database.store.save(exchange.drainChanges());
        exchange.close();
    }

    /** Alice (£1,000) buys 6 MANN at 100, sells 2 at 110, and leaves a buy limit and a stop-loss waiting. */
    private ExchangeService exchangeWithHistory() {
        ExchangeService exchange = start();
        exchange.openBotAccount("maker");
        exchange.openAccount("alice", 100_000);
        exchange.placeOrder("maker", "MANN", Side.SELL, OrderType.LIMIT, 100, 6);
        exchange.placeOrder("alice", "MANN", Side.BUY, OrderType.MARKET, 0, 6);
        exchange.placeOrder("maker", "MANN", Side.BUY, OrderType.LIMIT, 110, 2);
        exchange.placeOrder("alice", "MANN", Side.SELL, OrderType.MARKET, 0, 2);
        exchange.placeOrder("alice", "MANN", Side.BUY, OrderType.LIMIT, 90, 10);
        exchange.placeOrder("alice", "MANN", Side.SELL, OrderType.STOP, 0, 80, 3);
        exchange.placeOrder("maker", "MANN", Side.SELL, OrderType.LIMIT, 150, 5);   // a bot order: not saved
        return exchange;
    }

    @Test
    void aTradersMoneySharesOrdersAndFillsSurviveARestart() {
        ExchangeService before = exchangeWithHistory();
        AccountView expected = before.account("alice").orElseThrow();
        saveAndStop(before);

        ExchangeService after = start();
        AccountView alice = after.account("alice").orElseThrow();

        assertEquals(expected, alice);   // every number on the portfolio panel, in one go
        assertEquals(100_000 - 600 + 220, alice.cash());
        assertEquals(20, alice.realisedPnl());
        assertEquals(4, alice.positions().get(0).qty());
        assertEquals(1, alice.positions().get(0).available());   // 3 of the 4 are promised to the stop
        assertEquals(900, alice.reservedCash());
        assertEquals(2, alice.fills().size());
        after.close();
    }

    @Test
    void restoredOrdersAreRealOrders() {
        ExchangeService before = exchangeWithHistory();
        List<OpenOrder> orders = before.account("alice").orElseThrow().openOrders();
        long limitId = orders.get(0).orderId();
        saveAndStop(before);

        ExchangeService after = start();
        assertEquals(2, after.openOrderCount(), "alice's two orders are back; the maker's is not");
        assertTrue(after.book("MANN", 5).asks().isEmpty());

        // New orders get new ids, and the restored limit order trades like any other
        long newId = after.placeOrder("maker", "MANN", Side.SELL, OrderType.LIMIT, 90, 4).orderId();
        assertTrue(newId > orders.get(1).orderId());
        AccountView alice = after.account("alice").orElseThrow();
        assertEquals(8, alice.positions().get(0).qty());
        assertEquals(6, alice.openOrders().get(0).remaining());

        assertTrue(after.cancelOrder("alice", "MANN", limitId));
        assertEquals(0, after.account("alice").orElseThrow().reservedCash());

        // The stop still fires: a trade at 80 or below turns it into a market sell
        after.placeOrder("maker", "MANN", Side.BUY, OrderType.LIMIT, 80, 10);
        after.openBotAccount("taker");
        after.placeOrder("taker", "MANN", Side.SELL, OrderType.MARKET, 0, 1);
        assertEquals(5, after.account("alice").orElseThrow().positions().get(0).qty());
        after.close();
    }

    @Test
    void pricesDayStatisticsAndCandlesSurviveARestart() {
        ExchangeService before = exchangeWithHistory();
        now.addAndGet(60_000);
        before.placeOrder("maker", "MANN", Side.BUY, OrderType.LIMIT, 105, 1);
        before.placeOrder("alice", "MANN", Side.SELL, OrderType.MARKET, 0, 1);
        MarketSummary expected = before.market().get(0);
        List<Candle> expectedCandles = before.candles("MANN", "1m", 10);
        saveAndStop(before);

        ExchangeService after = start();
        assertEquals(expected, after.market().get(0));
        assertEquals(105, after.lastPrice("MANN"));
        assertEquals(2, expectedCandles.size());
        assertEquals(expectedCandles, after.candles("MANN", "1m", 10));
        assertEquals(new Candle(expectedCandles.get(0).start(), 100, 110, 100, 110, 8), expectedCandles.get(0));
        assertEquals(1, after.candles("MANN", "1h", 10).size());
        assertEquals(5_000, after.market().get(1).last(), "CLYD never traded: still at its start price");
        after.close();
    }

    @Test
    void savingTwiceOnlyWritesWhatChanged() {
        ExchangeService exchange = exchangeWithHistory();
        database.store.save(exchange.drainChanges());

        ExchangeStore.Snapshot nothingNew = exchange.drainChanges();
        assertTrue(nothingNew.accounts().isEmpty());
        assertTrue(nothingNew.fills().isEmpty());
        assertTrue(nothingNew.candles().isEmpty());
        assertNull(nothingNew.openOrders(), "open orders are unchanged, so they are not rewritten");
        database.store.save(nothingNew);

        // The candle that is still being built is updated in place, not inserted a second time
        exchange.placeOrder("maker", "MANN", Side.BUY, OrderType.LIMIT, 105, 1);
        exchange.placeOrder("alice", "MANN", Side.SELL, OrderType.MARKET, 0, 1);
        saveAndStop(exchange);

        assertEquals(3, database.jdbc.queryForObject("SELECT COUNT(*) FROM account_fill", Integer.class));
        assertEquals(1, database.jdbc.queryForObject("SELECT COUNT(*) FROM candle WHERE interval_seconds = 60", Integer.class));
        assertEquals(9, database.jdbc.queryForObject("SELECT volume FROM candle WHERE interval_seconds = 60", Long.class));
        assertEquals(0, database.jdbc.queryForObject("SELECT COUNT(*) FROM account_fill WHERE account_id = 'maker'", Integer.class));
    }

    @Test
    void anAccountInTheConfigKeepsItsSavedBalanceInsteadOfBeingReset() {
        ExchangeService before = exchangeWithHistory();
        saveAndStop(before);

        ExchangeService after = start();
        assertFalse(after.openAccount("alice", 100_000), "alice already exists");
        assertEquals(100_000 - 600 + 220, after.account("alice").orElseThrow().cash());
        after.close();
    }

    @Test
    void headlinesSurviveARestart() {
        ExchangeService exchange = start();
        NewsFeed feed = new NewsFeed(exchange, database.newsStore);
        feed.publish("MANN", "Technology", "Mannan Technologies wins a major contract", NewsFeed.Kind.GOOD);
        feed.publish(null, "Energy", "Strong demand lifts Energy shares", NewsFeed.Kind.GOOD);
        database.newsStore.save(feed.drainUnsaved());
        assertTrue(feed.drainUnsaved().isEmpty());

        NewsFeed restarted = new NewsFeed(exchange, database.newsStore);
        assertEquals(feed.latest(10), restarted.latest(10));
        restarted.publish("CLYD", "Technology", "Analysts upgrade Clyde Digital", NewsFeed.Kind.GOOD);
        assertEquals(3, restarted.latest(1).get(0).id(), "ids carry on from the saved ones");

        assertEquals(1, database.newsStore.deleteBefore(2));
        exchange.close();
    }

    @Test
    void oldMinuteCandlesCanBePruned() {
        ExchangeService exchange = exchangeWithHistory();
        saveAndStop(exchange);

        assertEquals(0, database.store.deleteMinuteCandlesBefore(now.get() - 60_000));
        assertEquals(1, database.store.deleteMinuteCandlesBefore(now.get() + 60_000));
        assertEquals(1, database.jdbc.queryForObject("SELECT COUNT(*) FROM candle", Integer.class), "the 1-hour candle is kept");
    }
}
