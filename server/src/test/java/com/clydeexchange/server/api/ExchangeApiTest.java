package com.clydeexchange.server.api;

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

/** Drives the real HTTP layer (JSON in, JSON out) against a fresh exchange for each test. */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ExchangeApiTest {

    @Autowired
    private MockMvc mvc;

    private ResultActions placeOrder(String json) throws Exception {
        return mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void crossingOrdersTradeAndShowUpInTradesAndBook() throws Exception {
        placeOrder("""
            {"symbol":"MANN","side":"SELL","type":"LIMIT","price":10100,"qty":30}""")
            .andExpect(status().isOk());

        placeOrder("""
            {"symbol":"MANN","side":"BUY","type":"LIMIT","price":10200,"qty":10}""")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.trades.length()").value(1))
            .andExpect(jsonPath("$.trades[0].price").value(10100))   // resting price, not 10200
            .andExpect(jsonPath("$.trades[0].qty").value(10));

        mvc.perform(get("/api/trades/MANN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));

        mvc.perform(get("/api/book/MANN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.asks[0].price").value(10100))
            .andExpect(jsonPath("$.asks[0].qty").value(20));
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
        placeOrder("""
            {"symbol":"MANN","side":"SELL","type":"LIMIT","price":10100,"qty":30}""")
            .andExpect(jsonPath("$.orderId").value(1));

        mvc.perform(delete("/api/orders/MANN/1")).andExpect(status().isNoContent());
        mvc.perform(delete("/api/orders/MANN/1")).andExpect(status().isNotFound());
    }

    @Test
    void symbolsAreListedInConfigOrder() throws Exception {
        mvc.perform(get("/api/symbols"))
            .andExpect(jsonPath("$[0]").value("MANN"))
            .andExpect(jsonPath("$[1]").value("CLYD"));
    }
}
