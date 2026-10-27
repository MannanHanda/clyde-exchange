package com.clydeexchange.server.ws;

import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;
import com.clydeexchange.server.service.ExchangeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Starts the real server on a random port, connects a real WebSocket client, and waits for a trade. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "exchange.sim.enabled=false")
class MarketDataWebSocketTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ExchangeService exchange;

    @Test
    void tradeIsPushedToConnectedClient() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocketSession session = new StandardWebSocketClient()
                .execute(new TextWebSocketHandler() {
                    @Override
                    protected void handleTextMessage(WebSocketSession s, TextMessage message) {
                        received.add(message.getPayload());
                    }
                }, "ws://localhost:" + port + "/ws/market")
                .get(5, TimeUnit.SECONDS);

        exchange.placeOrder("MANN", Side.SELL, OrderType.LIMIT, 10100, 10);
        exchange.placeOrder("MANN", Side.BUY, OrderType.LIMIT, 10100, 10);

        String tradesMessage = null;
        long deadline = System.currentTimeMillis() + 5_000;
        while (tradesMessage == null && System.currentTimeMillis() < deadline) {
            String msg = received.poll(500, TimeUnit.MILLISECONDS);
            if (msg != null && msg.contains("\"type\":\"trades\"")) tradesMessage = msg;
        }
        session.close();

        assertNotNull(tradesMessage, "no trades message within 5s; got: " + received);
        assertTrue(tradesMessage.contains("\"price\":10100"));
        assertTrue(tradesMessage.contains("\"symbol\":\"MANN\""));
    }
}
