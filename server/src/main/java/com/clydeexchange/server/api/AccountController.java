package com.clydeexchange.server.api;

import com.clydeexchange.server.account.AccountView;
import com.clydeexchange.server.auth.Leaderboard;
import com.clydeexchange.server.auth.RequestAuth;
import com.clydeexchange.server.service.ExchangeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class AccountController {

    private final ExchangeService exchange;
    private final RequestAuth requestAuth;
    private final Leaderboard leaderboard;

    public AccountController(ExchangeService exchange, RequestAuth requestAuth, Leaderboard leaderboard) {
        this.exchange = exchange;
        this.requestAuth = requestAuth;
        this.leaderboard = leaderboard;
    }

    /** Your own account: cash, positions, open orders and recent fills. There is no way to ask for anyone else's. */
    @GetMapping("/account")
    public ResponseEntity<AccountView> account(HttpServletRequest request) {
        return ResponseEntity.of(exchange.account(requestAuth.user(request)));
    }

    /** Public: every registered trader's account value and return, best first. */
    @GetMapping("/leaderboard")
    public List<Leaderboard.Row> leaderboard(@RequestParam(defaultValue = "20") int limit) {
        return leaderboard.top(Math.min(limit, 100));
    }
}
