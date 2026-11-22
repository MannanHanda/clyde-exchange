package com.clydeexchange.server.auth;

import com.clydeexchange.server.service.ExchangeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Every registered trader, ranked by return: how much their account is worth now compared with what they started with.
 * Ranking by return rather than by pounds is what makes it fair between people who started with different amounts.
 *
 * Anyone can ask for the leaderboard and working it out values every account on the exchange's one
 * writer thread, so the answer is kept for a couple of seconds and shared by everyone who asks in that time.
 */
@Service
public class Leaderboard {

    public record Row(int rank, String username, long equity, long startingCash, double returnPercent) {}

    private static final long KEEP_FOR_MS = 2_000;

    private final UserStore users;
    private final ExchangeService exchange;
    private final LongSupplier clock;
    private List<Row> latest = List.of();
    private long latestAt = Long.MIN_VALUE;

    @Autowired
    public Leaderboard(UserStore users, ExchangeService exchange) {
        this(users, exchange, System::currentTimeMillis);
    }

    public Leaderboard(UserStore users, ExchangeService exchange, LongSupplier clock) {
        this.users = users;
        this.exchange = exchange;
        this.clock = clock;
    }

    public synchronized List<Row> top(int limit) {
        long now = clock.getAsLong();
        if (latestAt == Long.MIN_VALUE || now - latestAt >= KEEP_FOR_MS) {
            latest = rank();
            latestAt = now;
        }
        return latest.subList(0, Math.max(0, Math.min(limit, latest.size())));
    }

    private List<Row> rank() {
        Map<String, Long> startingCash = users.startingCashByUser();
        List<Row> rows = new ArrayList<>();
        for (ExchangeService.Standing standing : exchange.standings()) {
            Long start = startingCash.get(standing.accountId());
            if (start == null || start <= 0) continue;   // an account nobody has registered for
            rows.add(new Row(0, standing.accountId(), standing.equity(), start, (standing.equity() - start) * 100.0 / start));
        }
        rows.sort(Comparator.comparingDouble(Row::returnPercent).reversed().thenComparing(Row::username));

        List<Row> ranked = new ArrayList<>();
        for (Row row : rows) {
            ranked.add(new Row(ranked.size() + 1, row.username(), row.equity(), row.startingCash(), row.returnPercent()));
        }
        return List.copyOf(ranked);
    }
}
