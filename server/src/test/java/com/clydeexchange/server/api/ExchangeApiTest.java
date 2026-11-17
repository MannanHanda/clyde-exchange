package com.clydeexchange.server.api;

import com.clydeexchange.server.service.ExchangeService;
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
 * "maker" is an unlimited bot account that supplies the other side; "alice" is a trader with £1,000,000.
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

    @BeforeEach
    void openMaker() {
        exchange.openBotAccount("maker");
    }

    private ResultActions placeOrder(String json) throws Exception {
        return mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void crossingOrdersTradeAndShowUpInTradesAndBook() throws Exception {
        placeOrder("""
            {"accountId":"maker","symbol":"MANN","side":"SELL","type":"LIMIT","price":10100,"qty":30}""")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.restingQty").value(30));

        placeOrder("""
            {"accountId":"alice","symbol":"MANN","side":"BUY","type":"LIMIT","price":10200,"qty":10}""")
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
        placeOrder("""
            {"accountId":"maker","symbol":"MANN","side":"SELL","type":"LIMIT","price":10000,"qty":50}""");
        placeOrder("""
            {"accountId":"alice","symbol":"MANN","side":"BUY","type":"MARKET","qty":20}""");
        placeOrder("""
            {"accountId":"alice","symbol":"CLYD","side":"BUY","type":"LIMIT","price":5000,"qty":10}""");

        mvc.perform(get("/api/accounts/alice"))
            .andExpect(status().isOk())
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
    void unknownAccountIs404OnReadAnd400OnOrder() throws Exception {
        mvc.perform(get("/api/accounts/nobody")).andExpect(status().isNotFound());
        placeOrder("""
            {"accountId":"nobody","symbol":"MANN","side":"BUY","type":"LIMIT","price":10000,"qty":1}""")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Unknown account nobody"));
    }

    @Test
    void orderYouCannotAffordIsA400() throws Exception {
        placeOrder("""
            {"accountId":"alice","symbol":"MANN","side":"BUY","type":"LIMIT","price":10000,"qty":10001}""")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(
                "Insufficient buying power: this order needs £1,000,100.00 but only £1,000,000.00 is available"));

        placeOrder("""
            {"accountId":"alice","symbol":"MANN","side":"SELL","type":"LIMIT","price":10000,"qty":1}""")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Insufficient shares: trying to sell 1 MANN but only 0 available"));
    }

    @Test
    void invalidOrderIsA400WithAMessage() throws Exception {
        placeOrder("""
            {"accountId":"alice","symbol":"MANN","side":"BUY","type":"LIMIT","price":10000,"qty":0}""")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Quantity must be > 0, was 0"));
    }

    @Test
    void unknownSymbolIsA400() throws Exception {
        mvc.perform(get("/api/book/NOPE")).andExpect(status().isBadRequest());
    }

    @Test
    void cancelRestingOrderIs204ThenUnknownIs404() throws Exception {
        placeOrder("""
            {"accountId":"maker","symbol":"MANN","side":"SELL","type":"LIMIT","price":10100,"qty":30}""")
            .andExpect(jsonPath("$.orderId").value(1));

        mvc.perform(delete("/api/orders/MANN/1?accountId=alice")).andExpect(status().isNotFound());   // not hers
        mvc.perform(delete("/api/orders/MANN/1?accountId=maker")).andExpect(status().isNoContent());
        mvc.perform(delete("/api/orders/MANN/1?accountId=maker")).andExpect(status().isNotFound());
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
    void stopOrderWaitsThenSellsWhenThePriceFalls() throws Exception {
        placeOrder("""
            {"accountId":"maker","symbol":"MANN","side":"SELL","type":"LIMIT","price":10000,"qty":10}""");
        placeOrder("""
            {"accountId":"alice","symbol":"MANN","side":"BUY","type":"MARKET","qty":10}""");
        placeOrder("""
            {"accountId":"alice","symbol":"MANN","side":"SELL","type":"STOP","stopPrice":9800,"qty":10}""")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.trades.length()").value(0))
            .andExpect(jsonPath("$.restingQty").value(10));
        mvc.perform(get("/api/accounts/alice"))
            .andExpect(jsonPath("$.openOrders[0].type").value("STOP"))
            .andExpect(jsonPath("$.openOrders[0].stopPrice").value(9800));

        placeOrder("""
            {"accountId":"maker","symbol":"MANN","side":"BUY","type":"LIMIT","price":9800,"qty":50}""");
        exchange.openBotAccount("seller");
        placeOrder("""
            {"accountId":"seller","symbol":"MANN","side":"SELL","type":"MARKET","qty":1}""");   // trades at 9800

        mvc.perform(get("/api/accounts/alice"))
            .andExpect(jsonPath("$.openOrders.length()").value(0))
            .andExpect(jsonPath("$.positions.length()").value(0))
            .andExpect(jsonPath("$.realisedPnl").value(-2000));   // 10 shares, £2.00 lost on each
    }

    @Test
    void aBigJumpHaltsTradingAndMakesTheNews() throws Exception {
        mvc.perform(get("/api/news")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        placeOrder("""
            {"accountId":"maker","symbol":"MANN","side":"SELL","type":"LIMIT","price":10000,"qty":1}""");
        placeOrder("""
            {"accountId":"maker","symbol":"MANN","side":"SELL","type":"LIMIT","price":11000,"qty":1}""");
        placeOrder("""
            {"accountId":"alice","symbol":"MANN","side":"BUY","type":"LIMIT","price":11000,"qty":2}""")
            .andExpect(jsonPath("$.trades.length()").value(2));   // 10000 then 11000: up 10% at once

        mvc.perform(get("/api/market")).andExpect(jsonPath("$[0].halted").value(true));
        mvc.perform(get("/api/news"))
            .andExpect(jsonPath("$[0].kind").value("HALT"))
            .andExpect(jsonPath("$[0].symbol").value("MANN"))
            .andExpect(jsonPath("$[0].headline").value("Trading in MANN halted after a 10.0% rise in under a minute"));
        placeOrder("""
            {"accountId":"alice","symbol":"MANN","side":"BUY","type":"LIMIT","price":11000,"qty":1}""")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Trading in MANN is halted for another 15 seconds"));
    }
}
