package com.clydeexchange.server.auth;

import com.clydeexchange.server.account.AccountView;
import com.clydeexchange.server.service.ExchangeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Who is this? Registration, login, and working out which user a request comes from.
 * A user owns the trading account whose id is their username.
 *
 * There are two ways to prove who you are:
 *   - a SESSION, for people. Logging in with a password gives the browser a long random token in a cookie.
 *   - an API KEY, for programs. A user creates one on the dashboard and their bot sends it with every request.
 *
 * Three secrets, two kinds of hashing, and none of the three is ever stored as it is:
 *   - Passwords are chosen by people, so they are short and guessable. They are hashed with BCrypt, which is
 *     slow on purpose (roughly a tenth of a second) so that trying millions of guesses is impractical.
 *   - Session tokens and API keys are 192 random bits made here. Nobody can guess those, so a fast
 *     SHA-256 hash is enough, and it has to be fast because it runs on every request.
 */
@Service
public class AuthService {

    /** A new key, shown to the user exactly once. */
    public record NewApiKey(String key, String label) {}

    public static final String KEY_PREFIX = "clyde_";
    public static final long SESSION_MS = 30L * 24 * 3_600_000;
    static final int MAX_KEYS_PER_USER = 5;
    static final int MAX_FAILED_LOGINS = 10;
    static final long FAILED_LOGIN_WINDOW_MS = 15 * 60_000;

    private static final Pattern USERNAME = Pattern.compile("[a-z0-9_]{3,20}");   // bot accounts contain a "-", so can never be registered
    private static final int MIN_PASSWORD = 8;
    private static final int MAX_PASSWORD_BYTES = 72;   // BCrypt ignores anything after 72 bytes

    private final UserStore store;
    private final ExchangeService exchange;
    private final AuthProperties properties;
    private final LongSupplier clock;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();
    private final String hashOfNothing = passwords.encode("there is no such user");
    private final Map<String, FailedLogins> failedLogins = new ConcurrentHashMap<>();

    private record FailedLogins(int count, long since) {}

    @Autowired
    public AuthService(UserStore store, ExchangeService exchange, AuthProperties properties) {
        this(store, exchange, properties, System::currentTimeMillis);
    }

    public AuthService(UserStore store, ExchangeService exchange, AuthProperties properties, LongSupplier clock) {
        this.store = store;
        this.exchange = exchange;
        this.properties = properties;
        this.clock = clock;
        // Users are saved the moment they register, but the exchange saves accounts up to a second later.
        // A crash in between leaves a user with no account, so give any such user their account back.
        store.startingCashByUser().forEach(exchange::openAccount);
    }

    public boolean signupOpen() {
        return properties.signupEnabled();
    }

    /**
     * Creates a user and their trading account, and logs them in.
     *
     * @return the session token for the browser's cookie
     */
    public String register(String username, String password) {
        if (!properties.signupEnabled()) throw new IllegalArgumentException("Registration is closed");
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("Username must be 3 to 20 characters: lower-case letters, digits or _");
        }
        if (password == null || password.length() < MIN_PASSWORD || tooLong(password)) {
            throw new IllegalArgumentException("Password must be 8 to 72 characters");
        }
        if (store.userCount() >= properties.maxUsers()) throw new IllegalArgumentException("Registration is full");

        // A trader's account that nobody owns yet (one from application.yml, or from before login existed) goes to
        // whoever registers its name, and the leaderboard measures them from what it is worth today.
        // Everyone else starts with the signup cash.
        Optional<AccountView> existing = exchange.account(username);
        if (existing.isPresent() && !exchange.isTrader(username)) throw new IllegalArgumentException("That username is taken");
        long startingCash = existing.map(AccountView::equity).filter(equity -> equity > 0).orElse(properties.signupCash());
        try {
            store.addUser(new UserStore.User(username, passwords.encode(password), startingCash), clock.getAsLong());
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("That username is taken");
        }
        exchange.openAccount(username, startingCash);   // does nothing if the account is already there
        return newSession(username);
    }

    /**
     * @return the session token for the browser's cookie
     * @throws NotLoggedInException     with the same message whether the username or the password was wrong
     * @throws TooManyRequestsException after too many wrong guesses at one username
     */
    public String login(String username, String password) {
        // No user has a name or a password like this, so there is nothing to check (and nothing to remember below)
        if (username == null || password == null || !USERNAME.matcher(username).matches() || tooLong(password)) {
            throw new NotLoggedInException("Wrong username or password");
        }
        long now = clock.getAsLong();
        FailedLogins failed = failedLogins.get(username);
        if (failed != null && failed.count() >= MAX_FAILED_LOGINS && now - failed.since() < FAILED_LOGIN_WINDOW_MS) {
            throw new TooManyRequestsException("Too many failed logins for this username: try again in 15 minutes");
        }

        Optional<UserStore.User> user = store.user(username);
        // Check a password even when there is no such user, so that the time taken does not give away which usernames exist
        boolean correct = passwords.matches(password, user.map(UserStore.User::passwordHash).orElse(hashOfNothing));
        if (user.isEmpty() || !correct) {
            if (failedLogins.size() > 10_000) failedLogins.clear();   // never let this map grow without limit
            failedLogins.merge(username, new FailedLogins(1, now), (old, first) ->
                    (now - old.since() < FAILED_LOGIN_WINDOW_MS) ? new FailedLogins(old.count() + 1, old.since()) : first);
            throw new NotLoggedInException("Wrong username or password");
        }
        failedLogins.remove(username);
        return newSession(username);
    }

    public void logout(String sessionToken) {
        if (sessionToken != null) store.deleteSession(sha256(sessionToken));
    }

    public Optional<String> userForSession(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) return Optional.empty();
        return store.sessionUser(sha256(sessionToken), clock.getAsLong());
    }

    public Optional<String> userForApiKey(String apiKey) {
        if (apiKey == null || !apiKey.startsWith(KEY_PREFIX)) return Optional.empty();
        return store.apiKeyUser(sha256(apiKey));
    }

    public NewApiKey createApiKey(String username, String label) {
        String name = (label == null || label.isBlank()) ? "my bot" : label.strip();
        if (name.length() > 40) throw new IllegalArgumentException("A key's name can be at most 40 characters");
        if (store.apiKeys(username).size() >= MAX_KEYS_PER_USER) {
            throw new IllegalArgumentException("You can have at most " + MAX_KEYS_PER_USER + " API keys: delete one first");
        }
        String key = KEY_PREFIX + randomToken();
        store.addApiKey(username, name, sha256(key), key.substring(0, 12), clock.getAsLong());
        return new NewApiKey(key, name);
    }

    public List<UserStore.ApiKeyInfo> apiKeys(String username) {
        return store.apiKeys(username);
    }

    public boolean deleteApiKey(String username, long id) {
        return store.deleteApiKey(username, id);
    }

    @Scheduled(initialDelay = 120_000, fixedDelay = 3_600_000)
    public void deleteExpiredSessions() {
        store.deleteSessionsExpiredBefore(clock.getAsLong());
    }

    private String newSession(String username) {
        String token = randomToken();
        store.addSession(sha256(token), username, clock.getAsLong() + SESSION_MS);
        return token;
    }

    /** 24 random bytes (192 bits) from the operating system's secure random source, as 32 URL-safe characters. */
    private String randomToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean tooLong(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // every Java has SHA-256
        }
    }
}
