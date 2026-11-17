package com.clydeexchange.server.store;

import com.clydeexchange.server.news.NewsFeed;
import com.clydeexchange.server.news.NewsStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** Headlines in the news_item table. */
@Component
public class JdbcNewsStore implements NewsStore {

    private final JdbcTemplate jdbc;

    public JdbcNewsStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<NewsFeed.Item> latest(int limit) {
        return jdbc.query("SELECT id, published_at, symbol, sector, headline, kind FROM news_item ORDER BY id DESC LIMIT ?",
                (row, n) -> new NewsFeed.Item(row.getLong("id"), row.getLong("published_at"), row.getString("symbol"),
                        row.getString("sector"), row.getString("headline"), NewsFeed.Kind.valueOf(row.getString("kind"))),
                limit);
    }

    @Override
    public void save(List<NewsFeed.Item> items) {
        jdbc.batchUpdate("INSERT INTO news_item (id, published_at, symbol, sector, headline, kind) VALUES (?, ?, ?, ?, ?, ?)",
                items.stream().map(i -> new Object[] {i.id(), i.timestamp(), i.symbol(), i.sector(), i.headline(),
                        i.kind().name()}).toList());
    }

    /** Removes every headline with an id below this one. */
    public int deleteBefore(long id) {
        return jdbc.update("DELETE FROM news_item WHERE id < ?", id);
    }
}
