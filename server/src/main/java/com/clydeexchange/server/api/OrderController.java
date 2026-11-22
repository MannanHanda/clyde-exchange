package com.clydeexchange.server.api;

import com.clydeexchange.server.auth.RequestAuth;
import com.clydeexchange.server.auth.TooManyRequestsException;
import com.clydeexchange.server.service.ExchangeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Placing and cancelling orders. Whose order it is always comes from the login cookie or API key, never from the request body. */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final ExchangeService exchange;
    private final RequestAuth requestAuth;
    private final RateLimiter rateLimiter;

    public OrderController(ExchangeService exchange, RequestAuth requestAuth,
                           @Value("${exchange.api.orders-per-second:20}") int ordersPerSecond) {
        this.exchange = exchange;
        this.requestAuth = requestAuth;
        this.rateLimiter = new RateLimiter(ordersPerSecond);
    }

    @PostMapping
    public ExchangeService.OrderResult place(@RequestBody PlaceOrderRequest order, HttpServletRequest request) {
        String accountId = allowedUser(request);
        long price = (order.price() == null) ? 0 : order.price();
        long stopPrice = (order.stopPrice() == null) ? 0 : order.stopPrice();
        long qty = (order.qty() == null) ? 0 : order.qty();
        return exchange.placeOrder(accountId, order.symbol(), order.side(), order.type(), price, stopPrice, qty);
    }

    @DeleteMapping("/{symbol}/{orderId}")
    public ResponseEntity<Void> cancel(@PathVariable String symbol, @PathVariable long orderId, HttpServletRequest request) {
        return exchange.cancelOrder(allowedUser(request), symbol, orderId)
                ? ResponseEntity.noContent().build()   // 204: cancelled
                : ResponseEntity.notFound().build();   // 404: unknown, already filled, or not this account's order
    }

    /** Who is asking, as long as they are not sending orders and cancels faster than the limit. */
    private String allowedUser(HttpServletRequest request) {
        String accountId = requestAuth.user(request);
        if (!rateLimiter.allow(accountId, System.currentTimeMillis())) {
            throw new TooManyRequestsException("Too many orders: slow down and try again in a second");
        }
        return accountId;
    }
}
