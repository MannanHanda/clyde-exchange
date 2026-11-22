package com.clydeexchange.server.api;

import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;
import com.clydeexchange.server.service.ExchangeService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Drives the real HTTP layer (JSON in, JSON out) against a fresh exchange for each test.
 * "maker" is an unlimited bot account that supplies the other side. Bots have no login, so its orders
 * go straight to the exchange. "alice" is a trader with £1,000,000 who registers and trades over HTTP.
 */
@SpringBootTest(properties = {
        "exchange.sim.enabled=false",   // no bots, so the book only has our orders
        "exchange.accounts.starting-cash.alice=100000000"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ExchangeApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ExchangeService exchange;

    private Cookie alice;   // her login cookie

    @BeforeEach
    void openMakerAndLogAliceIn() throws Exception {
        exchange.openBotAccount("maker");
        alice = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"username":"alice","password":"correct horse"}"""))
            .andExpect(status().isOk())
            .andReturn().getResponse().getCookie("clyde_session");
    }

    /** An order from alice. */
    private ResultActions placeOrder(String json) throws Exception {
        return mvc.perform(post("/api/orders").cookie(alice).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private void makerOrder(String symbol, Side side, long price, long qty) {
        exchange.placeOrder("maker", symbol, side, OrderType.LIMIT, price, qty);
    }

    @Test
    void crossingOrdersTradeAndShowUpInTradesAndBook() throws Exception {
        makerOrder("MANN", Side.SELL, 10100, 30);

        placeOrder("""
            {"symbol":"MANN","side":"BUY","type":"LIMIT","price":10200,"qty":10}""")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.trades.length()").value(1))
            .andExpect(jsonPath("$.trades[0].price").value(10100))   // resting price, not 10200
            .andExpect(jsonPath("$.trades[0].qty").value(10))
            .andExpect(jsonPath("$.restingQty").value(0));

        mvc.perform(get("/api/trades/MANN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));

        mvc.perform(get("/api/book/MANN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.asks[0].price").value(10100))
            .andExpect(jsonPath("$.asks[0].qty").value(20));
    }

    @Test
    void accountShowsCashPositionsOpenOrdersAndFills() throws Exception {
        makerOrder("MANN", Side.SELL, 10000, 50);
        placeOrder("""
            {"symbol":"MANN","side":"BUY","type":"MARKET","qty":20}""");
        placeOrder("""
            {"symbol":"CLYD","side":"BUY","type":"LIMIT","price":5000,"qty":10}""");

        mvc.perform(get("/api/account").cookie(alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountId").value("alice"))
            .andExpect(jsonPath("$.cash").value(100_000_000 - 200_000))
            .andExpect(jsonPath("$.reservedCash").value(50_000))
            .andExpect(jsonPath("$.buyingPower").value(100_000_000 - 250_000))
            .andExpect(jsonPath("$.equity").value(100_000_000))
            .andExpect(jsonPath("$.positions[0].symbol").value("MANN"))
            .andExpect(jsonPath("$.positions[0].qty").value(20))
            .andExpect(jsonPath("$.positions[0].averagePrice").value(10000))
            .andExpect(jsonPath("$.openOrders.length()").value(1))
            .andExpect(jsonPath("$.openOrders[0].symbol").value("CLYD"))
            .andExpect(jsonPath("$.fills[0].side").value("BUY"));
    }

    @Test
    void withoutLoggingInYouCanLookAtTheMarketButNotTrade() throws Exception {
        mvc.perform(get("/api/market")).andExpect(status().isOk());
        mvc.perform(get("/api/account"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.detail").value("Log in first"));
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("""
            {"symbol":"MANN","side":"BUY","type":"LIMIT","price":10000,"qty":1}"""))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/accounts/alice")).andExpect(status().isNotFound());   // the old way to read any account is gone
        mvc.perform(get("/api/book/MANN")).andExpect(jsonPath("$.bids.length()").value(0));
    }

    @Test
    void orderYouCannotAffordIsA400() throws Exception {
        placeOrder("""
            {"symbol":"MANN","side":"BUY","type":"LIMIT","price":10000,"qty":10001}""")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(
                "Insufficient buying power: this order needs £1,000,100.00 but only £1,000,000.00 is available"));

        placeOrder("""
            {"symbol":"MANN","side":"SELL","type":"LIMIT","price":10000,"qty":1}""")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Insufficient shares: trying to sell 1 MANN but only 0 available"));
    }

    @Test
    void invalidOrderIsA400WithAMessage() throws Exception {
        placeOrder("""
            {"symbol":"MANN","side":"BUY","type":"LIMIT","price":10000,"qty":0}""")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Quantity must be > 0, was 0"));
    }

    @Test
    void unknownSymbolIsA400() throws Exception {
        mvc.perform(get("/api/book/NOPE")).andExpect(status().isBadRequest());
    }

    @Test
    void cancelRestingOrderIs204ThenUnknownIs404() throws Exception {
        makerOrder("MANN", Side.SELL, 10100, 30);   // order 1
        placeOrder("""
            {"symbol":"MANN","side":"BUY","type":"LIMIT","price":9900,"qty":5}""")
            .andExpect(jsonPath("$.orderId").value(2));

        mvc.perform(delete("/api/orders/MANN/1").cookie(alice)).andExpect(status().isNotFound());   // not hers
        mvc.perform(delete("/api/orders/MANN/2")).andExpect(status().isUnauthorized());            // nobody is asking
        mvc.perform(delete("/api/orders/MANN/2").cookie(alice)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/orders/MANN/2").cookie(alice)).andExpect(status().isNotFound());
    }

    @Test
    void symbolsAreListedInConfigOrder() throws Exception {
        mvc.perform(get("/api/symbols"))
            .andExpect(jsonPath("$[0]").value("MANN"))
            .andExpect(jsonPath("$[1]").value("CLYD"));
    }

    @Test
    void marketListsEveryInstrumentWithItsSectorAndStartPrice() throws Exception {
        mvc.perform(get("/api/market"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(10))
            .andExpect(jsonPath("$[0].symbol").value("MANN"))
            .andExpect(jsonPath("$[0].name").value("Mannan Technologies"))
            .andExpect(jsonPath("$[0].sector").value("Technology"))
            .andExpect(jsonPath("$[0].last").value(10000))
            .andExpect(jsonPath("$[0].halted").value(false));
    }

    @Test
    void candlesShowThePriceHistory() throws Exception {
        makerOrder("MANN", Side.SELL, 10000, 30);
        placeOrder("""
            {"symbol":"MANN","side":"BUY","type":"LIMIT","price":10000,"qty":10}""");

        mvc.perform(get("/api/candles/MANN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].open").value(10000))
            .andExpect(jsonPath("$[0].close").value(10000))
            .andExpect(jsonPath("$[0].volume").value(10));
        mvc.perform(get("/api/candles/MANN?interval=1d&limit=5")).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/candles/CLYD")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/candles/MANN?interval=2m")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/market")).andExpect(jsonPath("$[0].previousClose").value(0));
    }

    @Test
    void theHealthCheckSaysTheServerAndItsDatabaseAreUp() throws Exception {
        mvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void stopOrderWaitsThenSellsWhenThePriceFalls() throws Exception {
        makerOrder("MANN", Side.SELL, 10000, 10);
        placeOrder("""
            {"symbol":"MANN","side":"BUY","type":"MARKET","qty":10}""");
        placeOrder("""
            {"symbol":"MANN","side":"SELL","type":"STOP","stopPrice":9800,"qty":10}""")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.trades.length()").value(0))
            .andExpect(jsonPath("$.restingQty").value(10));
        mvc.perform(get("/api/account").cookie(alice))
            .andExpect(jsonPath("$.openOrders[0].type").value("STOP"))
            .andExpect(jsonPath("$.openOrders[0].stopPrice").value(9800));

        makerOrder("MANN", Side.BUY, 9800, 50);
        exchange.openBotAccount("seller");
        exchange.placeOrder("seller", "MANN", Side.SELL, OrderType.MARKET, 0, 1);   // trades at 9800

        mvc.perform(get("/api/account").cookie(alice))
            .andExpect(jsonPath("$.openOrders.length()").value(0))
            .andExpect(jsonPath("$.positions.length()").value(0))
            .andExpect(jsonPath("$.realisedPnl").value(-2000));   // 10 shares, £2.00 lost on each
    }

    @Test
    void aBigJumpHaltsTradingAndMakesTheNews() throws Exception {
        mvc.perform(get("/api/news")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        makerOrder("MANN", Side.SELL, 10000, 1);
        makerOrder("MANN", Side.SELL, 11000, 1);
        placeOrder("""
            {"symbol":"MANN","side":"BUY","type":"LIMIT","price":11000,"qty":2}""")
            .andExpect(jsonPath("$.trades.length()").value(2));   // 10000 then 11000: up 10% at once

        mvc.perform(get("/api/market")).andExpect(jsonPath("$[0].halted").value(true));
        mvc.perform(get("/api/news"))
            .andExpect(jsonPath("$[0].kind").value("HALT"))
            .andExpect(jsonPath("$[0].symbol").value("MANN"))
            .andExpect(jsonPath("$[0].headline").value("Trading in MANN halted after a 10.0% rise in under a minute"));
        placeOrder("""
            {"symbol":"MANN","side":"BUY","type":"LIMIT","price":11000,"qty":1}""")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Trading in MANN is halted for another 15 seconds"));
    }

    @Test
    void theLeaderboardIsPublicAndShowsRegisteredTradersButNotBots() throws Exception {
        makerOrder("MANN", Side.SELL, 10000, 100);
        placeOrder("""
            {"symbol":"MANN","side":"BUY","type":"MARKET","qty":100}""");
        // The price rises to £102 without alice doing anything: her 100 shares are now worth £200 more
        makerOrder("MANN", Side.SELL, 10200, 1);
        exchange.openBotAccount("buyer");
        exchange.placeOrder("buyer", "MANN", Side.BUY, OrderType.MARKET, 0, 1);

        mvc.perform(get("/api/leaderboard"))   // no cookie
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].rank").value(1))
            .andExpect(jsonPath("$[0].username").value("alice"))
            .andExpect(jsonPath("$[0].startingCash").value(100_000_000))   // what her account was worth when she registered
            .andExpect(jsonPath("$[0].equity").value(100_020_000))
            .andExpect(jsonPath("$[0].returnPercent").value(0.02));
    }
}
