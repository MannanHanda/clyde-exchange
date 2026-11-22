package com.clydeexchange.server.api;

import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;
import com.clydeexchange.server.service.ExchangeService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Login, API keys and "you can only touch your own account", through the real HTTP layer.
 * All the tests share one exchange and one database, so each uses its own usernames and its own symbol.
 */
@SpringBootTest(properties = {
        "exchange.sim.enabled=false",
        "exchange.api.orders-per-second=5"})
@AutoConfigureMockMvc
class AuthApiTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ExchangeService exchange;

    @Autowired
    private JdbcTemplate jdbc;

    private ResultActions send(String path, String username, String password) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)));
    }

    /** Registers a new user and returns their login cookie. */
    private Cookie register(String username) throws Exception {
        return send("/api/auth/register", username, PASSWORD)
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("clyde_session");
    }

    private static String order(String symbol, String side, long price, long qty) {
        return "{\"symbol\":\"%s\",\"side\":\"%s\",\"type\":\"LIMIT\",\"price\":%d,\"qty\":%d}".formatted(symbol, side, price, qty);
    }

    /** Creates an API key for whoever owns the cookie and returns the key itself. */
    private String newKey(Cookie cookie, String label) throws Exception {
        MvcResult result = mvc.perform(post("/api/keys").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"%s\"}".formatted(label)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(startsWith("clyde_")))
                .andReturn();
        return result.getResponse().getContentAsString().split("\"key\":\"")[1].split("\"")[0];
    }

    @Test
    void registeringLogsYouInAndGivesYouAnAccountWithTheSignupCash() throws Exception {
        send("/api/auth/register", "bob", PASSWORD)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("bob"))
            .andExpect(cookie().httpOnly("clyde_session", true))        // scripts on the page cannot read it
            .andExpect(cookie().sameSite("clyde_session", "Strict"))    // other websites cannot use it
            .andExpect(cookie().path("clyde_session", "/"))
            .andExpect(cookie().maxAge("clyde_session", 30 * 24 * 3600));

        Cookie cookie = send("/api/auth/login", "bob", PASSWORD)
            .andExpect(status().isOk())
            .andReturn().getResponse().getCookie("clyde_session");

        mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(jsonPath("$.username").value("bob"));
        mvc.perform(get("/api/account").cookie(cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountId").value("bob"))
            .andExpect(jsonPath("$.cash").value(10_000_000));
    }

    @Test
    void askingWhoYouAreWhenLoggedOutIsNotAnError() throws Exception {
        mvc.perform(get("/api/auth/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value(nullValue()))
            .andExpect(jsonPath("$.signupOpen").value(true));
        mvc.perform(get("/api/auth/me").cookie(new Cookie("clyde_session", "made-up-token")))
            .andExpect(jsonPath("$.username").value(nullValue()));
    }

    @Test
    void noSecretIsStoredAsItIs() throws Exception {
        Cookie cookie = register("hashed");
        String key = newKey(cookie, "bot");

        String passwordHash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE username = 'hashed'", String.class);
        assertTrue(passwordHash.startsWith("$2"), "a BCrypt hash, not " + passwordHash);
        assertFalse(passwordHash.contains(PASSWORD));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM login_session WHERE token_hash = ?", Integer.class, cookie.getValue()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM login_session WHERE username = 'hashed'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM api_key WHERE key_hash = ?", Integer.class, key));
        assertEquals(key.substring(0, 12),
                jdbc.queryForObject("SELECT key_start FROM api_key WHERE username = 'hashed'", String.class));
    }

    @Test
    void aWrongPasswordAndAnUnknownUserLookExactlyTheSame() throws Exception {
        register("carol");
        send("/api/auth/login", "carol", "not her password")
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.detail").value("Wrong username or password"))
            .andExpect(cookie().doesNotExist("clyde_session"));
        send("/api/auth/login", "no_such_user", PASSWORD)
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.detail").value("Wrong username or password"));
    }

    @Test
    void badUsernamesAndPasswordsAreRejected() throws Exception {
        String badName = "Username must be 3 to 20 characters: lower-case letters, digits or _";
        for (String name : new String[] {"ab", "Dave", "bot-maker", "dave smith", "a".repeat(21)}) {
            send("/api/auth/register", name, PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(badName));
        }
        send("/api/auth/register", "dave", "short")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Password must be 8 to 72 characters"));
        send("/api/auth/register", "dave", "x".repeat(73)).andExpect(status().isBadRequest());

        register("dave");
        send("/api/auth/register", "dave", "a different password")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("That username is taken"));
        send("/api/auth/login", "dave", "a different password").andExpect(status().isUnauthorized());   // the first dave keeps the name
    }

    @Test
    void aBotsAccountCannotBeRegistered() throws Exception {
        exchange.openBotAccount("robot");
        send("/api/auth/register", "robot", PASSWORD)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("That username is taken"));
    }

    @Test
    void loggingOutEndsTheSessionOnTheServer() throws Exception {
        Cookie cookie = register("erin");
        mvc.perform(get("/api/account").cookie(cookie)).andExpect(status().isOk());

        mvc.perform(post("/api/auth/logout").cookie(cookie))
            .andExpect(status().isNoContent())
            .andExpect(cookie().maxAge("clyde_session", 0));   // tells the browser to delete it

        // Someone who copied the cookie before the logout cannot use it afterwards
        mvc.perform(get("/api/account").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void anOrderAlwaysBelongsToWhoeverIsLoggedIn() throws Exception {
        Cookie frank = register("frank");
        Cookie grace = register("grace");

        // frank says the order is grace's. Nobody is listening: there is no such field.
        mvc.perform(post("/api/orders").cookie(frank).contentType(MediaType.APPLICATION_JSON).content("""
                {"accountId":"grace","symbol":"KELV","side":"BUY","type":"LIMIT","price":18000,"qty":5}"""))
            .andExpect(status().isOk());

        mvc.perform(get("/api/account").cookie(frank)).andExpect(jsonPath("$.openOrders.length()").value(1));
        mvc.perform(get("/api/account").cookie(grace)).andExpect(jsonPath("$.openOrders.length()").value(0));

        long orderId = exchange.account("frank").orElseThrow().openOrders().get(0).orderId();
        mvc.perform(delete("/api/orders/KELV/" + orderId + "?accountId=frank").cookie(grace)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/orders/KELV/" + orderId).cookie(frank)).andExpect(status().isNoContent());
    }

    @Test
    void anApiKeyTradesLikeItsOwnerButCannotManageKeys() throws Exception {
        Cookie cookie = register("heidi");
        String key = newKey(cookie, "momentum bot");

        mvc.perform(get("/api/keys").cookie(cookie))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].label").value("momentum bot"))
            .andExpect(jsonPath("$[0].keyStart").value(key.substring(0, 12)))
            .andExpect(jsonPath("$[0].key").doesNotExist());   // the key is shown once, when it is made

        mvc.perform(get("/api/account").header("X-API-Key", key))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountId").value("heidi"));
        mvc.perform(post("/api/orders").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(order("NSEA", "BUY", 3400, 10)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.restingQty").value(10));

        // A key can trade, but only a logged-in person can make or delete keys
        mvc.perform(get("/api/keys").header("X-API-Key", key)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/keys").header("X-API-Key", key)).andExpect(status().isUnauthorized());

        mvc.perform(get("/api/account").header("X-API-Key", "clyde_this-is-not-a-real-key"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.detail").value("Unknown API key"));
        // A bad key is not rescued by a good cookie: the request said which one it wanted to use
        mvc.perform(get("/api/account").cookie(cookie).header("X-API-Key", "nonsense")).andExpect(status().isUnauthorized());
    }

    @Test
    void aDeletedKeyStopsWorkingAndYouCannotDeleteSomeoneElses() throws Exception {
        Cookie ivan = register("ivan");
        Cookie judy = register("judy");
        String key = newKey(ivan, "to delete");
        long id = jdbc.queryForObject("SELECT id FROM api_key WHERE username = 'ivan'", Long.class);

        mvc.perform(delete("/api/keys/" + id).cookie(judy)).andExpect(status().isNotFound());
        mvc.perform(get("/api/account").header("X-API-Key", key)).andExpect(status().isOk());

        mvc.perform(delete("/api/keys/" + id).cookie(ivan)).andExpect(status().isNoContent());
        mvc.perform(get("/api/account").header("X-API-Key", key)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/keys").cookie(ivan)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void thereIsALimitToHowManyKeysOneUserCanHave() throws Exception {
        Cookie cookie = register("kate");
        for (int i = 1; i <= 5; i++) newKey(cookie, "bot " + i);
        mvc.perform(post("/api/keys").cookie(cookie))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("You can have at most 5 API keys: delete one first"));
    }

    @Test
    void tooManyWrongPasswordsLockTheUsernameForAWhile() throws Exception {
        register("leo");
        for (int i = 0; i < 10; i++) {
            send("/api/auth/login", "leo", "guess number " + i).andExpect(status().isUnauthorized());
        }
        // Now even the right password is refused, so a guesser gets at most 10 tries every 15 minutes
        send("/api/auth/login", "leo", PASSWORD)
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.detail").value("Too many failed logins for this username: try again in 15 minutes"));
    }

    @Test
    void sendingOrdersTooFastIsA429() throws Exception {
        Cookie cookie = register("mallory");
        exchange.openBotAccount("maker");
        exchange.placeOrder("maker", "TIDE", Side.SELL, OrderType.LIMIT, 1300, 1);

        int accepted = 0;
        int refused = 0;
        for (int i = 0; i < 30; i++) {   // the limit in this test is 5 a second, and 30 orders take far less than 6 seconds
            int status = mvc.perform(post("/api/orders").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                    .content(order("TIDE", "BUY", 1200, 1))).andReturn().getResponse().getStatus();
            if (status == 200) accepted++;
            else if (status == 429) refused++;
        }
        assertEquals(30, accepted + refused);
        assertTrue(accepted >= 5, "the first five always get through, got " + accepted);
        assertTrue(refused > 0, "some must be refused");
        assertEquals(accepted, exchange.account("mallory").orElseThrow().openOrders().size());   // a refused order never reaches the book
    }
}
