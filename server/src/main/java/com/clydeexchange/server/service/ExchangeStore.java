package com.clydeexchange.server.service;

import com.clydeexchange.engine.book.OpenOrder;
import com.clydeexchange.server.account.Account;

import java.util.List;

/**
 * Where the exchange keeps what must survive a restart. ExchangeService only knows this interface;
 * the real one (JdbcExchangeStore) talks to a database, and tests can use a simple in-memory fake.
 *
 * What is saved: every account's cash and positions, the traders' open orders and fills, each symbol's
 * last price and day statistics, the price candles, and the next order id.
 * What is not: the bots' open orders (they place new ones every tick anyway) and the trades between
 * bots (there are millions; the candles are their summary).
 */
public interface ExchangeStore {

    record SavedPosition(String symbol, long qty, long cost) {}

    record SavedAccount(String id, boolean bot, boolean unlimited, long cash, long realisedPnl,
                        List<SavedPosition> positions) {}

    /** day is the clock time at which the trading day that open, high, low and volume belong to began. */
    record SavedSymbol(String symbol, long last, long day, long open, long high, long low, long volume,
                       long previousClose) {}

    record SavedFill(String accountId, Account.Fill fill) {}

    record SavedCandle(String symbol, int intervalSeconds, Candle candle) {}

    /**
     * One consistent picture of the exchange, taken between two orders.
     *
     * When saving, it only holds what changed since the last save, and openOrders is null if the
     * traders' open orders are the same as last time. When loading, it holds everything:
     * fills newest first, candles oldest first.
     */
    record Snapshot(long nextOrderId, List<SavedAccount> accounts, List<SavedSymbol> symbols,
                    List<OpenOrder> openOrders, List<SavedFill> fills, List<SavedCandle> candles) {

        public static Snapshot empty() {
            return new Snapshot(0, List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    /**
     * @param fillsPerAccount how many of each trader's latest fills to bring back
     * @param minuteCandlesSince only 1-minute candles that start at or after this clock time
     * @param hourCandlesSince the same for 1-hour candles
     * @return what was saved, or Snapshot.empty() the first time the exchange ever starts
     */
    Snapshot load(int fillsPerAccount, long minuteCandlesSince, long hourCandlesSince);

    /** Writes the changes as one unit: after a crash the database holds all of a snapshot or none of it. */
    void save(Snapshot changes);
}
