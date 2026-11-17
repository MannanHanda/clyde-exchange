package com.clydeexchange.server.store;

import com.clydeexchange.engine.book.OpenOrder;
import com.clydeexchange.engine.model.OrderType;
import com.clydeexchange.engine.model.Side;
import com.clydeexchange.server.account.Account;
import com.clydeexchange.server.service.Candle;
import com.clydeexchange.server.service.ExchangeStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the exchange's state in a SQL database, using plain SQL through Spring's JdbcTemplate.
 * The tables are created by Flyway from resources/db/migration before this class is ever used.
 *
 * The SQL sticks to what both H2 and PostgreSQL understand. In particular there is no
 * "insert or update" statement that is spelled the same in both, so a row is saved by trying an
 * UPDATE first and doing an INSERT only if the UPDATE found nothing to change.
 */
@Component
public class JdbcExchangeStore implements ExchangeStore {

    private static final String NEXT_ORDER_ID = "next_order_id";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcExchangeStore(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    @Override
    public Snapshot load(int fillsPerAccount, long minuteCandlesSince, long hourCandlesSince) {
        List<Long> nextOrderId = jdbc.queryForList(
                "SELECT number_value FROM exchange_meta WHERE name = ?", Long.class, NEXT_ORDER_ID);

        Map<String, List<SavedPosition>> positions = new HashMap<>();
        jdbc.query("SELECT account_id, symbol, qty, cost FROM holding ORDER BY account_id, symbol", row -> {
            positions.computeIfAbsent(row.getString("account_id"), id -> new ArrayList<>())
                    .add(new SavedPosition(row.getString("symbol"), row.getLong("qty"), row.getLong("cost")));
        });
        List<SavedAccount> accounts = jdbc.query(
                "SELECT id, bot, unlimited, cash, realised_pnl FROM account ORDER BY id",
                (row, n) -> new SavedAccount(row.getString("id"), row.getBoolean("bot"), row.getBoolean("unlimited"),
                        row.getLong("cash"), row.getLong("realised_pnl"),
                        positions.getOrDefault(row.getString("id"), List.of())));

        List<SavedSymbol> symbols = jdbc.query(
                "SELECT symbol, last_price, day_start, open_price, high_price, low_price, volume, previous_close FROM symbol_state",
                (row, n) -> new SavedSymbol(row.getString("symbol"), row.getLong("last_price"), row.getLong("day_start"),
                        row.getLong("open_price"), row.getLong("high_price"), row.getLong("low_price"),
                        row.getLong("volume"), row.getLong("previous_close")));

        List<OpenOrder> openOrders = jdbc.query(
                "SELECT order_id, account_id, symbol, side, order_type, price, stop_price, quantity, remaining FROM open_order ORDER BY order_id",
                (row, n) -> new OpenOrder(row.getLong("order_id"), row.getString("account_id"), row.getString("symbol"),
                        Side.valueOf(row.getString("side")), OrderType.valueOf(row.getString("order_type")),
                        row.getLong("price"), row.getLong("stop_price"), row.getLong("quantity"), row.getLong("remaining")));

        List<SavedFill> fills = new ArrayList<>();
        for (SavedAccount account : accounts) {
            if (account.bot()) continue;
            fills.addAll(jdbc.query(
                    "SELECT order_id, symbol, side, price, qty, traded_at FROM account_fill WHERE account_id = ? ORDER BY id DESC LIMIT ?",
                    (row, n) -> new SavedFill(account.id(), new Account.Fill(row.getLong("order_id"), row.getString("symbol"),
                            Side.valueOf(row.getString("side")), row.getLong("price"), row.getLong("qty"), row.getLong("traded_at"))),
                    account.id(), fillsPerAccount));
        }

        List<SavedCandle> candles = jdbc.query(
                "SELECT symbol, interval_seconds, start_time, open_price, high_price, low_price, close_price, volume FROM candle "
                        + "WHERE (interval_seconds = 60 AND start_time >= ?) OR (interval_seconds = 3600 AND start_time >= ?) "
                        + "ORDER BY start_time",
                (row, n) -> new SavedCandle(row.getString("symbol"), row.getInt("interval_seconds"),
                        new Candle(row.getLong("start_time"), row.getLong("open_price"), row.getLong("high_price"),
                                row.getLong("low_price"), row.getLong("close_price"), row.getLong("volume"))),
                minuteCandlesSince, hourCandlesSince);

        return new Snapshot(nextOrderId.isEmpty() ? 0 : nextOrderId.get(0), accounts, symbols, openOrders, fills, candles);
    }

    /** One transaction: if the server dies half-way through, the database rolls back to the previous snapshot. */
    @Override
    public void save(Snapshot changes) {
        transaction.executeWithoutResult(status -> {
            if (jdbc.update("UPDATE exchange_meta SET number_value = ? WHERE name = ?", changes.nextOrderId(), NEXT_ORDER_ID) == 0) {
                jdbc.update("INSERT INTO exchange_meta (name, number_value) VALUES (?, ?)", NEXT_ORDER_ID, changes.nextOrderId());
            }
            changes.accounts().forEach(this::saveAccount);
            changes.symbols().forEach(this::saveSymbol);
            if (changes.openOrders() != null) replaceOpenOrders(changes.openOrders());
            insertFills(changes.fills());
            changes.candles().forEach(this::saveCandle);
        });
    }

    /** Removes 1-minute candles older than the given time. The 1-hour candles are small enough to keep for ever. */
    public int deleteMinuteCandlesBefore(long time) {
        return jdbc.update("DELETE FROM candle WHERE interval_seconds = 60 AND start_time < ?", time);
    }

    private void saveAccount(SavedAccount a) {
        if (jdbc.update("UPDATE account SET cash = ?, realised_pnl = ? WHERE id = ?", a.cash(), a.realisedPnl(), a.id()) == 0) {
            jdbc.update("INSERT INTO account (id, bot, unlimited, cash, realised_pnl) VALUES (?, ?, ?, ?, ?)",
                    a.id(), a.bot(), a.unlimited(), a.cash(), a.realisedPnl());
        }
        // An account has at most one position per symbol, so replacing them all is simplest
        jdbc.update("DELETE FROM holding WHERE account_id = ?", a.id());
        jdbc.batchUpdate("INSERT INTO holding (account_id, symbol, qty, cost) VALUES (?, ?, ?, ?)",
                a.positions().stream().map(p -> new Object[] {a.id(), p.symbol(), p.qty(), p.cost()}).toList());
    }

    private void saveSymbol(SavedSymbol s) {
        int updated = jdbc.update("UPDATE symbol_state SET last_price = ?, day_start = ?, open_price = ?, high_price = ?, "
                        + "low_price = ?, volume = ?, previous_close = ? WHERE symbol = ?",
                s.last(), s.day(), s.open(), s.high(), s.low(), s.volume(), s.previousClose(), s.symbol());
        if (updated == 0) {
            jdbc.update("INSERT INTO symbol_state (symbol, last_price, day_start, open_price, high_price, low_price, volume, "
                            + "previous_close) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    s.symbol(), s.last(), s.day(), s.open(), s.high(), s.low(), s.volume(), s.previousClose());
        }
    }

    private void replaceOpenOrders(List<OpenOrder> orders) {
        jdbc.update("DELETE FROM open_order");
        jdbc.batchUpdate("INSERT INTO open_order (order_id, account_id, symbol, side, order_type, price, stop_price, quantity, "
                        + "remaining) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                orders.stream().map(o -> new Object[] {o.orderId(), o.accountId(), o.symbol(), o.side().name(),
                        o.type().name(), o.price(), o.stopPrice(), o.quantity(), o.remaining()}).toList());
    }

    private void insertFills(List<SavedFill> fills) {
        jdbc.batchUpdate("INSERT INTO account_fill (account_id, order_id, symbol, side, price, qty, traded_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                fills.stream().map(f -> new Object[] {f.accountId(), f.fill().orderId(), f.fill().symbol(),
                        f.fill().side().name(), f.fill().price(), f.fill().qty(), f.fill().timestamp()}).toList());
    }

    private void saveCandle(SavedCandle saved) {
        Candle c = saved.candle();
        int updated = jdbc.update("UPDATE candle SET open_price = ?, high_price = ?, low_price = ?, close_price = ?, volume = ? "
                        + "WHERE symbol = ? AND interval_seconds = ? AND start_time = ?",
                c.open(), c.high(), c.low(), c.close(), c.volume(), saved.symbol(), saved.intervalSeconds(), c.start());
        if (updated == 0) {
            jdbc.update("INSERT INTO candle (symbol, interval_seconds, start_time, open_price, high_price, low_price, close_price, "
                            + "volume) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    saved.symbol(), saved.intervalSeconds(), c.start(), c.open(), c.high(), c.low(), c.close(), c.volume());
        }
    }
}
