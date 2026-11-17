package com.clydeexchange.server.store;

import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;
import com.clydeexchange.server.account.AccountView;
import com.clydeexchange.server.news.NewsFeed;
import com.clydeexchange.server.service.Candle;
import com.clydeexchange.server.service.CircuitBreaker;
import com.clydeexchange.server.service.ExchangeService;
import com.clydeexchange.server.service.Instrument;
import com.clydeexchange.server.service.MarketSummary;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The other tests use H2 because it starts instantly. Production (docker-compose) uses PostgreSQL,
 * and two databases never agree on every detail of SQL, so this test runs the table script and a
 * full save-and-restart against a real PostgreSQL server that it starts itself.
 */
class PostgresTest {

    private static final List<Instrument> INSTRUMENTS = List.of(
            new Instrument("MANN", "Mannan Technologies", "Technology", 10_000));

    @Test
    void theExchangeSurvivesARestartOnPostgres() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start()) {
            TestDatabase database = new TestDatabase(postgres.getPostgresDatabase());
            AtomicLong now = new AtomicLong(1_800_000_000_000L);

            ExchangeService before = new ExchangeService(INSTRUMENTS, CircuitBreaker.Settings.OFF, now::get, database.store);
            before.openBotAccount("maker");
            before.openAccount("alice", 100_000);
            before.placeOrder("maker", "MANN", Side.SELL, OrderType.LIMIT, 100, 6);
            before.placeOrder("alice", "MANN", Side.BUY, OrderType.MARKET, 0, 6);
            before.placeOrder("alice", "MANN", Side.BUY, OrderType.LIMIT, 90, 10);
            before.placeOrder("alice", "MANN", Side.SELL, OrderType.STOP, 0, 80, 3);
            database.store.save(before.drainChanges());

            // A second save updates rows that already exist (the same candle, the same account)
            before.placeOrder("maker", "MANN", Side.BUY, OrderType.LIMIT, 110, 2);
            before.placeOrder("alice", "MANN", Side.SELL, OrderType.MARKET, 0, 2);
            database.store.save(before.drainChanges());

            NewsFeed feed = new NewsFeed(before, database.newsStore);
            feed.publish("MANN", "Technology", "Mannan Technologies wins a major contract", NewsFeed.Kind.GOOD);
            feed.publish(null, "Technology", "Chip shortage hits Technology shares", NewsFeed.Kind.BAD);   // no symbol: NULL
            database.newsStore.save(feed.drainUnsaved());

            AccountView alice = before.account("alice").orElseThrow();
            MarketSummary market = before.market().get(0);
            List<Candle> candles = before.candles("MANN", "1m", 10);
            before.close();

            ExchangeService after = new ExchangeService(INSTRUMENTS, CircuitBreaker.Settings.OFF, now::get, database.store);
            assertEquals(alice, after.account("alice").orElseThrow());
            assertEquals(2, alice.fills().size());
            assertEquals(2, alice.openOrders().size());
            assertEquals(market, after.market().get(0));
            assertEquals(candles, after.candles("MANN", "1m", 10));
            assertEquals(feed.latest(10), new NewsFeed(after, database.newsStore).latest(10));

            assertEquals(1, database.store.deleteMinuteCandlesBefore(now.get() + 60_000));
            assertEquals(1, database.newsStore.deleteBefore(2));
            after.close();
        }
    }
}
