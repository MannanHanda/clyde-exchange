package com.clydeexchange.server.api;

import com.clydeexchange.server.account.AccountView;
import com.clydeexchange.server.service.ExchangeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final ExchangeService exchange;

    public AccountController(ExchangeService exchange) {
        this.exchange = exchange;
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<AccountView> account(@PathVariable String accountId) {
        return ResponseEntity.of(exchange.account(accountId));   // 404 if there is no such account
    }
}
