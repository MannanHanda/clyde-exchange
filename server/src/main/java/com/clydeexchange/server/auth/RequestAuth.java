package com.clydeexchange.server.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

import java.time.Duration;
import java.util.Optional;

/**
 * Reads who an HTTP request is from. Every endpoint that touches an account starts by calling user()
 * or sessionUser(): there is no way to name an account in the request itself, so an endpoint that
 * forgets to ask simply has no account to act on.
 */
@Component
public class RequestAuth {

    public static final String COOKIE = "clyde_session";
    public static final String API_KEY_HEADER = "X-API-Key";

    private final AuthService auth;

    public RequestAuth(AuthService auth) {
        this.auth = auth;
    }

    /** The user this request is from, by API key if it sends one and by login cookie if not. */
    public String user(HttpServletRequest request) {
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey != null) {
            return auth.userForApiKey(apiKey).orElseThrow(() -> new NotLoggedInException("Unknown API key"));
        }
        return sessionUser(request);
    }

    /** For things only a person at the dashboard may do, such as creating API keys: the login cookie is the only way in. */
    public String sessionUser(HttpServletRequest request) {
        return loggedInUser(request).orElseThrow(() -> new NotLoggedInException("Log in first"));
    }

    public Optional<String> loggedInUser(HttpServletRequest request) {
        return auth.userForSession(sessionToken(request));
    }

    public String sessionToken(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, COOKIE);
        return (cookie == null) ? null : cookie.getValue();
    }

    /**
     * The cookie that keeps a browser logged in.
     *   HttpOnly: JavaScript on the page cannot read it, so a script injected into the page cannot steal it.
     *   SameSite=Strict: the browser only sends it on requests made by this site's own pages, so another
     *     website cannot place orders by making your browser call this one (cross-site request forgery).
     *   Secure: when the site is on https, never send it over plain http.
     */
    public ResponseCookie sessionCookie(String token, HttpServletRequest request) {
        return cookie(token, Duration.ofMillis(AuthService.SESSION_MS), request);
    }

    /** A cookie that has already expired: the browser deletes its copy. */
    public ResponseCookie noCookie(HttpServletRequest request) {
        return cookie("", Duration.ZERO, request);
    }

    private static ResponseCookie cookie(String value, Duration maxAge, HttpServletRequest request) {
        return ResponseCookie.from(COOKIE, value)
                .httpOnly(true).sameSite("Strict").secure(request.isSecure()).path("/").maxAge(maxAge).build();
    }
}
