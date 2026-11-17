package com.clydeexchange.server.news;

import com.clydeexchange.server.service.ExchangeService;
import com.clydeexchange.server.service.Instrument;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * The exchange's news wire: the latest headlines, newest first. The simulator publishes company and
 * sector news here, and the feed adds an item itself whenever the exchange halts trading in a symbol.
 *
 * Headlines arrive from the simulator thread and the exchange's writer thread and are read by web
 * requests, so every method is synchronized. Only the last 50 are kept in memory. They are also saved
 * to the database (by StateSaver, about once a second), and the latest 50 are read back at startup.
 */
@Component
public class NewsFeed {

    public enum Kind { GOOD, BAD, HALT }

    /** symbol is null for news about a whole sector. */
    public record Item(long id, long timestamp, String symbol, String sector, String headline, Kind kind) {}

    private static final int CAPACITY = 50;

    private final Deque<Item> items = new ArrayDeque<>();   // newest first
    private final List<Item> unsaved = new ArrayList<>();
    private long nextId = 1;

    public NewsFeed(ExchangeService exchange, NewsStore store) {
        List<Item> saved = store.latest(CAPACITY);
        items.addAll(saved);
        if (!saved.isEmpty()) nextId = saved.get(0).id() + 1;

        // Runs on the exchange's writer thread, so it must not call back into the exchange
        // (instruments() is the exception: it is a plain read-only list)
        exchange.addHaltListener(halt -> {
            String sector = exchange.instruments().stream()
                    .filter(i -> i.symbol().equals(halt.symbol())).map(Instrument::sector).findFirst().orElse("");
            String headline = String.format(Locale.UK, "Trading in %s halted after a %.1f%% %s in under a minute",
                    halt.symbol(), Math.abs(halt.movePercent()), (halt.movePercent() > 0) ? "rise" : "fall");
            publish(halt.symbol(), sector, headline, Kind.HALT);
        });
    }

    public synchronized void publish(String symbol, String sector, String headline, Kind kind) {
        Item item = new Item(nextId++, System.currentTimeMillis(), symbol, sector, headline, kind);
        items.addFirst(item);
        if (items.size() > CAPACITY) items.removeLast();
        unsaved.add(item);
        if (unsaved.size() > 10 * CAPACITY) unsaved.remove(0);   // nobody is saving: don't let them pile up
    }

    /** Newest first. */
    public synchronized List<Item> latest(int limit) {
        return items.stream().limit(Math.max(0, limit)).toList();
    }

    /** The headlines published since this was last called, oldest first. */
    public synchronized List<Item> drainUnsaved() {
        List<Item> drained = List.copyOf(unsaved);
        unsaved.clear();
        return drained;
    }
}
