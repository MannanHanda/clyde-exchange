package com.clydeexchange.server.ws;

import com.clydeexchange.engine.book.BookSnapshot;
import com.clydeexchange.server.service.ExchangeService;
import com.clydeexchange.server.service.TradeRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Pushes live market data to every connected browser on /ws/market.
 *
 * The writer thread only drops each trade into a lock-free queue (instant). Every 200 ms this class
 * drains the queue and sends one batch of trades, plus a fresh book snapshot per symbol.
 * So a slow or dead browser can never slow down matching, and the browser gets at most
 * 5 updates a second instead of one per trade.
 *
 * Messages:  {"type":"trades","trades":[...]}   and   {"type":"book","book":{symbol,bids,asks}}
 */
@Component
public class MarketDataPublisher extends TextWebSocketHandler {

    public record TradesMessage(String type, List<TradeRecord> trades) {}
    public record BookMessage(String type, BookSnapshot book) {}

    private static final Logger log = LoggerFactory.getLogger(MarketDataPublisher.class);
    private static final int BOOK_DEPTH = 10;

    private final ExchangeService exchange;
    private final JsonMapper json;
    private final Queue<TradeRecord> pendingTrades = new ConcurrentLinkedQueue<>();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public MarketDataPublisher(ExchangeService exchange, JsonMapper json) {
        this.exchange = exchange;
        this.json = json;
        exchange.addTradeListener(pendingTrades::add);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // The decorator makes sends safe from any thread and drops a client that falls too far behind
        sessions.put(session.getId(), new ConcurrentWebSocketSessionDecorator(session, 2_000, 512 * 1024));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
    }

    @Scheduled(fixedRate = 200)
    public void publish() {
        List<TradeRecord> trades = new ArrayList<>();
        for (TradeRecord t; (t = pendingTrades.poll()) != null; ) trades.add(t);   // drain even with no viewers
        if (sessions.isEmpty()) return;

        if (!trades.isEmpty()) broadcast(new TradesMessage("trades", trades));
        for (String symbol : exchange.symbols()) {
            broadcast(new BookMessage("book", exchange.book(symbol, BOOK_DEPTH)));
        }
    }

    private void broadcast(Object message) {
        TextMessage text = new TextMessage(json.writeValueAsString(message));
        for (WebSocketSession session : sessions.values()) {
            try {
                session.sendMessage(text);
            } catch (IOException | RuntimeException e) {
                log.debug("Dropping WebSocket session {}: {}", session.getId(), e.getMessage());
                sessions.remove(session.getId());
            }
        }
    }
}
