package com.clydeexchange.server.auth;

import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;
import com.clydeexchange.server.service.ExchangeService;
import com.clydeexchange.server.store.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The parts of login that depend on time or on a restart, which are awkward to reach over HTTP.
 * The database is real (H2 in memory); the clock is a number the test moves by hand.
 */
class AuthServiceTest {

    private static final long MINUTE = 60_000;
    private static final long DAY = 24 * 60 * MINUTE;
    private static final String PASSWORD = "correct horse battery";

    private final TestDatabase database = new TestDatabase();
    private final UserStore store = new UserStore(database.jdbc);
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private final ExchangeService exchange = new ExchangeService(List.of("MANN"));
    private final AuthService auth = service(exchange, new AuthProperties(10_000_000, true, 1000));

    private AuthService service(ExchangeService exchange, AuthProperties properties) {
        return new AuthService(store, exchange, properties, now::get);
    }

    @AfterEach
    void stop() {
        exchange.close();
    }

    @Test
    void aSessionLastsThirtyDays() {
        String token = auth.register("alice", PASSWORD);
        assertEquals(Optional.of("alice"), auth.userForSession(token));

        now.addAndGet(30 * DAY - 1);
        assertEquals(Optional.of("alice"), auth.userForSession(token));
        now.addAndGet(1);
        assertEquals(Optional.empty(), auth.userForSession(token));

        // The dead row is still in the table until the hourly clean-up removes it
        assertEquals(1, database.jdbc.queryForObject("SELECT COUNT(*) FROM login_session", Integer.class));
        auth.deleteExpiredSessions();
        assertEquals(0, database.jdbc.queryForObject("SELECT COUNT(*) FROM login_session", Integer.class));
    }

    @Test
    void eachLoginIsItsOwnSessionSoLoggingOutOneBrowserLeavesTheOtherAlone() {
        String laptop = auth.register("alice", PASSWORD);
        String phone = auth.login("alice", PASSWORD);
        assertNotEquals(laptop, phone);

        auth.logout(laptop);
        assertEquals(Optional.empty(), auth.userForSession(laptop));
        assertEquals(Optional.of("alice"), auth.userForSession(phone));
    }

    @Test
    void tokensThatWereNeverIssuedMeanNobody() {
        auth.register("alice", PASSWORD);
        assertEquals(Optional.empty(), auth.userForSession(null));
        assertEquals(Optional.empty(), auth.userForSession(""));
        assertEquals(Optional.empty(), auth.userForSession("alice"));
        assertEquals(Optional.empty(), auth.userForApiKey(null));
        assertEquals(Optional.empty(), auth.userForApiKey("clyde_guess"));
    }

    @Test
    void theLockAfterTenWrongPasswordsLiftsAfterFifteenMinutes() {
        auth.register("alice", PASSWORD);
        for (int i = 0; i < 10; i++) {
            assertThrows(NotLoggedInException.class, () -> auth.login("alice", "a wrong guess"));
        }
        assertThrows(TooManyRequestsException.class, () -> auth.login("alice", PASSWORD));

        now.addAndGet(15 * MINUTE);
        assertNotNull(auth.login("alice", PASSWORD));
    }

    @Test
    void aCorrectLoginForgetsEarlierMistakes() {
        auth.register("alice", PASSWORD);
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 9; i++) {
                assertThrows(NotLoggedInException.class, () -> auth.login("alice", "a wrong guess"));
            }
            assertNotNull(auth.login("alice", PASSWORD));   // 27 mistakes in all, but never 10 in a row
        }
    }

    @Test
    void registrationCanBeClosedOrFull() {
        AuthService closed = service(exchange, new AuthProperties(10_000_000, false, 1000));
        assertEquals("Registration is closed",
                assertThrows(IllegalArgumentException.class, () -> closed.register("alice", PASSWORD)).getMessage());

        AuthService small = service(exchange, new AuthProperties(10_000_000, true, 1));
        small.register("alice", PASSWORD);
        assertEquals("Registration is full",
                assertThrows(IllegalArgumentException.class, () -> small.register("bobby", PASSWORD)).getMessage());
        assertNotNull(closed.login("alice", PASSWORD));   // closing registration does not lock existing users out
    }

    @Test
    void registeringTheNameOfAnUnownedAccountTakesItOverAsItIs() {
        exchange.openBotAccount("maker");
        exchange.openAccount("mannan", 500_000);
        exchange.placeOrder("maker", "MANN", Side.SELL, OrderType.LIMIT, 100, 10);
        exchange.placeOrder("mannan", "MANN", Side.BUY, OrderType.MARKET, 0, 10);

        auth.register("mannan", PASSWORD);

        assertEquals(499_000, exchange.account("mannan").orElseThrow().cash());        // not reset to the signup cash
        assertEquals(10, exchange.account("mannan").orElseThrow().positions().get(0).qty());
        assertEquals(500_000, store.user("mannan").orElseThrow().startingCash());      // cash 499,000 + shares 1,000
    }

    @Test
    void aUserWhoseAccountWasLostInACrashGetsItBackWhenTheServerStarts() {
        auth.register("alice", PASSWORD);
        String key = auth.createApiKey("alice", "bot").key();

        // The user row was saved at once; pretend the server died before the exchange saved her account
        try (ExchangeService restarted = new ExchangeService(List.of("MANN"))) {
            assertTrue(restarted.account("alice").isEmpty());
            AuthService after = service(restarted, new AuthProperties(10_000_000, true, 1000));

            assertEquals(10_000_000, restarted.account("alice").orElseThrow().cash());
            assertEquals(Optional.of("alice"), after.userForApiKey(key));   // keys and sessions live in the database
        }
    }
}
