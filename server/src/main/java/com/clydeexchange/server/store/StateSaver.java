package com.clydeexchange.server.store;

import com.clydeexchange.server.news.NewsFeed;
import com.clydeexchange.server.service.ExchangeService;
import com.clydeexchange.server.service.ExchangeStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Writes the exchange's state to the database about once a second, and once more as the server shuts down.
 *
 * This is called "write-behind": trading happens in memory at full speed, and the database follows a
 * moment later. The price of that speed is that if the server is killed without warning, up to about
 * a second of activity is lost. What survives is always one consistent picture (a snapshot is saved
 * in a single transaction), never half of one.
 *
 * If the database cannot be reached, the snapshots wait in a queue and are written, in order, when it comes back.
 */
@Component
public class StateSaver {

    private static final Logger log = LoggerFactory.getLogger(StateSaver.class);

    private static final int MAX_WAITING = 3_600;                             // about an hour of snapshots
    private static final long KEEP_MINUTE_CANDLES_MS = 2 * 24 * 3_600_000L;   // the exchange only ever reloads one day
    private static final long KEEP_NEWS_ITEMS = 500;

    private final ExchangeService exchange;
    private final NewsFeed newsFeed;
    private final JdbcExchangeStore store;
    private final JdbcNewsStore newsStore;
    private final Deque<ExchangeStore.Snapshot> waiting = new ArrayDeque<>();
    private final List<NewsFeed.Item> waitingNews = new ArrayList<>();
    private boolean failing;

    public StateSaver(ExchangeService exchange, NewsFeed newsFeed, JdbcExchangeStore store, JdbcNewsStore newsStore) {
        this.exchange = exchange;
        this.newsFeed = newsFeed;
        this.store = store;
        this.newsStore = newsStore;
    }

    /** synchronized because the scheduler and the shutdown hook could otherwise both be saving at once. */
    @Scheduled(fixedDelay = 1000)
    public synchronized void save() {
        waiting.addLast(exchange.drainChanges());
        waitingNews.addAll(newsFeed.drainUnsaved());
        try {
            while (!waiting.isEmpty()) {
                store.save(waiting.peekFirst());
                waiting.removeFirst();   // only once it is safely in the database
            }
            if (!waitingNews.isEmpty()) {
                newsStore.save(waitingNews);
                waitingNews.clear();
            }
            if (failing) log.info("The database is back: everything that was waiting has been saved");
            failing = false;
        } catch (RuntimeException e) {
            if (!failing) log.error("Could not save to the database. Trading carries on; will keep retrying", e);
            failing = true;
            if (waiting.size() > MAX_WAITING) {
                waiting.removeFirst();
                log.error("The database has been unreachable for too long: the oldest unsaved changes were dropped");
            }
        }
    }

    /** Old rows the exchange will never read again are deleted, so the database does not grow for ever. */
    @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
    public void prune() {
        try {
            int candles = store.deleteMinuteCandlesBefore(System.currentTimeMillis() - KEEP_MINUTE_CANDLES_MS);
            List<NewsFeed.Item> newest = newsFeed.latest(1);
            int news = newest.isEmpty() ? 0 : newsStore.deleteBefore(newest.get(0).id() - KEEP_NEWS_ITEMS);
            if (candles + news > 0) log.info("Pruned {} old 1-minute candles and {} old headlines", candles, news);
        } catch (RuntimeException e) {
            log.warn("Pruning old rows failed; it will be tried again in an hour", e);
        }
    }

    @PreDestroy
    public void saveOnShutdown() {
        save();
        log.info("Exchange state saved");
    }
}
