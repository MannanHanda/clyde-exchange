package com.clydeexchange.server.api;

import com.clydeexchange.server.service.ExchangeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final ExchangeService exchange;

    public OrderController(ExchangeService exchange) {
        this.exchange = exchange;
    }

    @PostMapping
    public ExchangeService.OrderResult place(@RequestBody PlaceOrderRequest request) {
        long price = (request.price() == null) ? 0 : request.price();
        long stopPrice = (request.stopPrice() == null) ? 0 : request.stopPrice();
        long qty = (request.qty() == null) ? 0 : request.qty();
        return exchange.placeOrder(request.accountId(), request.symbol(), request.side(), request.type(),
                price, stopPrice, qty);
    }

    @DeleteMapping("/{symbol}/{orderId}")
    public ResponseEntity<Void> cancel(@PathVariable String symbol, @PathVariable long orderId,
                                       @RequestParam String accountId) {
        return exchange.cancelOrder(accountId, symbol, orderId)
                ? ResponseEntity.noContent().build()   // 204: cancelled
                : ResponseEntity.notFound().build();   // 404: unknown, already filled, or not this account's order
    }
}
