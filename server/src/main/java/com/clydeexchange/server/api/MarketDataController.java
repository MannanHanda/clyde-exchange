package com.clydeexchange.server.api;

import com.clydeexchange.engine.book.BookSnapshot;
import com.clydeexchange.server.service.ExchangeService;
import com.clydeexchange.server.service.TradeRecord;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class MarketDataController {

    private final ExchangeService exchange;

    public MarketDataController(ExchangeService exchange) {
        this.exchange = exchange;
    }

    @GetMapping("/symbols")
    public Set<String> symbols() {
        return exchange.symbols();
    }

    @GetMapping("/book/{symbol}")
    public BookSnapshot book(@PathVariable String symbol, @RequestParam(defaultValue = "10") int depth) {
        return exchange.book(symbol, depth);
    }

    @GetMapping("/trades/{symbol}")
    public List<TradeRecord> trades(@PathVariable String symbol, @RequestParam(defaultValue = "50") int limit) {
        return exchange.recentTrades(symbol, limit);
    }
}
