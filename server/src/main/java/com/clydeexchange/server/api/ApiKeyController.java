package com.clydeexchange.server.api;

import com.clydeexchange.server.auth.AuthService;
import com.clydeexchange.server.auth.RequestAuth;
import com.clydeexchange.server.auth.UserStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * A user's API keys, for their own trading programs. Only a logged-in browser may use these endpoints:
 * a key cannot create or delete keys, so a leaked key can be cut off but can never dig itself in.
 */
@RestController
@RequestMapping("/api/keys")
public class ApiKeyController {

    public record NewKeyRequest(String label) {}

    private final AuthService auth;
    private final RequestAuth requestAuth;

    public ApiKeyController(AuthService auth, RequestAuth requestAuth) {
        this.auth = auth;
        this.requestAuth = requestAuth;
    }

    @GetMapping
    public List<UserStore.ApiKeyInfo> keys(HttpServletRequest request) {
        return auth.apiKeys(requestAuth.sessionUser(request));
    }

    /** The only time the key itself is ever sent: after this, even the server only has its hash. */
    @PostMapping
    public AuthService.NewApiKey create(@RequestBody(required = false) NewKeyRequest body, HttpServletRequest request) {
        return auth.createApiKey(requestAuth.sessionUser(request), (body == null) ? null : body.label());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, HttpServletRequest request) {
        return auth.deleteApiKey(requestAuth.sessionUser(request), id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();   // no such key, or it is someone else's
    }
}
