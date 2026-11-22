package com.clydeexchange.server.api;

import com.clydeexchange.server.auth.AuthService;
import com.clydeexchange.server.auth.RequestAuth;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Registering, logging in and out. Both of the first two answer with the cookie that keeps the browser logged in. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record Credentials(String username, String password) {}

    /** @param username null when nobody is logged in */
    public record Me(String username, boolean signupOpen) {}

    private final AuthService auth;
    private final RequestAuth requestAuth;

    public AuthController(AuthService auth, RequestAuth requestAuth) {
        this.auth = auth;
        this.requestAuth = requestAuth;
    }

    @PostMapping("/register")
    public ResponseEntity<Me> register(@RequestBody Credentials credentials, HttpServletRequest request) {
        return loggedIn(credentials.username(), auth.register(credentials.username(), credentials.password()), request);
    }

    @PostMapping("/login")
    public ResponseEntity<Me> login(@RequestBody Credentials credentials, HttpServletRequest request) {
        return loggedIn(credentials.username(), auth.login(credentials.username(), credentials.password()), request);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        auth.logout(requestAuth.sessionToken(request));   // forget the session here, not just in the browser
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, requestAuth.noCookie(request).toString())
                .build();
    }

    /** The dashboard asks this when it loads. Being logged out is a normal answer here, not an error. */
    @GetMapping("/me")
    public Me me(HttpServletRequest request) {
        return new Me(requestAuth.loggedInUser(request).orElse(null), auth.signupOpen());
    }

    private ResponseEntity<Me> loggedIn(String username, String sessionToken, HttpServletRequest request) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, requestAuth.sessionCookie(sessionToken, request).toString())
                .body(new Me(username, auth.signupOpen()));
    }
}
