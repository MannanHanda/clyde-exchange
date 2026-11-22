package com.clydeexchange.server.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The SQL for users, login sessions and API keys.
 *
 * Unlike the exchange's own state, these are read and written straight away, on the web request's
 * thread: logging in is rare and can afford to wait for the database, and none of it touches the order book.
 */
@Component
public class UserStore {

    public record User(String username, String passwordHash, long startingCash) {}

    /** What can safely be shown about a key. The key itself is not stored anywhere. */
    public record ApiKeyInfo(long id, String label, String keyStart, long createdAt) {}

    private final JdbcTemplate jdbc;

    public UserStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<User> user(String username) {
        return jdbc.query("SELECT username, password_hash, starting_cash FROM app_user WHERE username = ?",
                (row, n) -> new User(row.getString("username"), row.getString("password_hash"), row.getLong("starting_cash")),
                username).stream().findFirst();
    }

    /** Every username with the cash that user started with. */
    public Map<String, Long> startingCashByUser() {
        Map<String, Long> startingCash = new HashMap<>();
        jdbc.query("SELECT username, starting_cash FROM app_user",
                row -> { startingCash.put(row.getString("username"), row.getLong("starting_cash")); });
        return startingCash;
    }

    public int userCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class);
    }

    /** Throws DuplicateKeyException if the username is taken: the primary key decides, so two people cannot both win a race for a name. */
    public void addUser(User user, long now) {
        jdbc.update("INSERT INTO app_user (username, password_hash, starting_cash, created_at) VALUES (?, ?, ?, ?)",
                user.username(), user.passwordHash(), user.startingCash(), now);
    }

    public void addSession(String tokenHash, String username, long expiresAt) {
        jdbc.update("INSERT INTO login_session (token_hash, username, expires_at) VALUES (?, ?, ?)", tokenHash, username, expiresAt);
    }

    public Optional<String> sessionUser(String tokenHash, long now) {
        return jdbc.queryForList("SELECT username FROM login_session WHERE token_hash = ? AND expires_at > ?",
                String.class, tokenHash, now).stream().findFirst();
    }

    public void deleteSession(String tokenHash) {
        jdbc.update("DELETE FROM login_session WHERE token_hash = ?", tokenHash);
    }

    public int deleteSessionsExpiredBefore(long now) {
        return jdbc.update("DELETE FROM login_session WHERE expires_at <= ?", now);
    }

    public void addApiKey(String username, String label, String keyHash, String keyStart, long now) {
        jdbc.update("INSERT INTO api_key (username, label, key_hash, key_start, created_at) VALUES (?, ?, ?, ?, ?)",
                username, label, keyHash, keyStart, now);
    }

    public Optional<String> apiKeyUser(String keyHash) {
        return jdbc.queryForList("SELECT username FROM api_key WHERE key_hash = ?", String.class, keyHash).stream().findFirst();
    }

    public List<ApiKeyInfo> apiKeys(String username) {
        return jdbc.query("SELECT id, label, key_start, created_at FROM api_key WHERE username = ? ORDER BY id",
                (row, n) -> new ApiKeyInfo(row.getLong("id"), row.getString("label"), row.getString("key_start"), row.getLong("created_at")),
                username);
    }

    /** The username is part of the WHERE, so nobody can delete someone else's key by guessing its id. */
    public boolean deleteApiKey(String username, long id) {
        return jdbc.update("DELETE FROM api_key WHERE username = ? AND id = ?", username, id) > 0;
    }
}
