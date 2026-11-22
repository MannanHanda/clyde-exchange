package com.clydeexchange.server.auth;

import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;
import com.clydeexchange.server.service.ExchangeService;
import com.clydeexchange.server.store.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class LeaderboardTest {

    private final TestDatabase database = new TestDatabase();
    private final UserStore store = new UserStore(database.jdbc);
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private final ExchangeService exchange = new ExchangeService(List.of("MANN"));
    private final Leaderboard leaderboard = new Leaderboard(store, exchange, now::get);

    @AfterEach
    void stop() {
        exchange.close();
    }

    private void user(String username, long startingCash) {
        store.addUser(new UserStore.User(username, "not a real hash", startingCash), now.get());
        exchange.openAccount(username, startingCash);
    }

    /** A bot pair trades one share at this price, which moves the last price everyone's shares are valued at. */
    private void lastPrice(long price) {
        exchange.placeOrder("maker", "MANN", Side.SELL, OrderType.LIMIT, price, 1);
        exchange.placeOrder("maker2", "MANN", Side.BUY, OrderType.MARKET, 0, 1);
    }

    @Test
    void tradersAreRankedByReturnNotByHowMuchMoneyTheyHave() {
        exchange.openBotAccount("maker");
        exchange.openBotAccount("maker2");
        user("rich", 100_000_000);   // £1,000,000
        user("small", 1_000_000);    // £10,000
        user("idle", 5_000_000);
        exchange.openAccount("unowned", 9_000_000);   // an account nobody has registered for

        // Both buy at £100; small puts in a much bigger share of their money
        exchange.placeOrder("maker", "MANN", Side.SELL, OrderType.LIMIT, 10_000, 150);
        exchange.placeOrder("rich", "MANN", Side.BUY, OrderType.MARKET, 0, 100);
        exchange.placeOrder("small", "MANN", Side.BUY, OrderType.MARKET, 0, 50);
        lastPrice(11_000);   // up 10%

        List<Leaderboard.Row> rows = leaderboard.top(10);
        assertEquals(List.of("small", "rich", "idle"), rows.stream().map(Leaderboard.Row::username).toList());
        assertEquals(List.of(1, 2, 3), rows.stream().map(Leaderboard.Row::rank).toList());

        Leaderboard.Row small = rows.get(0);
        assertEquals(1_050_000, small.equity());          // 50 shares gained £10 each
        assertEquals(5.0, small.returnPercent(), 1e-9);
        Leaderboard.Row rich = rows.get(1);
        assertEquals(100_100_000, rich.equity());         // made twice as many pounds, but only 0.1%
        assertEquals(0.1, rich.returnPercent(), 1e-9);
        assertEquals(0.0, rows.get(2).returnPercent());

        assertEquals(1, leaderboard.top(1).size());
        assertEquals(0, leaderboard.top(-5).size());
    }

    @Test
    void theAnswerIsReusedForTwoSecondsSoThePublicCannotKeepTheExchangeBusy() {
        exchange.openBotAccount("maker");
        exchange.openBotAccount("maker2");
        user("alice", 1_000_000);
        exchange.placeOrder("maker", "MANN", Side.SELL, OrderType.LIMIT, 10_000, 10);
        exchange.placeOrder("alice", "MANN", Side.BUY, OrderType.MARKET, 0, 10);
        assertEquals(1_000_000, leaderboard.top(10).get(0).equity());

        lastPrice(12_000);
        now.addAndGet(1_999);
        assertEquals(1_000_000, leaderboard.top(10).get(0).equity());   // still the old answer
        now.addAndGet(1);
        assertEquals(1_020_000, leaderboard.top(10).get(0).equity());
    }
}
