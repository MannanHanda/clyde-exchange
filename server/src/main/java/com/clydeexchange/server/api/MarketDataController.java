package com.clydeexchange.server.api;

import com.clydeexchange.engine.book.BookSnapshot;
import com.clydeexchange.server.news.NewsFeed;
import com.clydeexchange.server.service.ExchangeService;
import com.clydeexchange.server.service.MarketSummary;
import com.clydeexchange.server.service.TradeRecord;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class MarketDataController {

    private final ExchangeService exchange;
    private final NewsFeed newsFeed;

    public MarketDataController(ExchangeService exchange, NewsFeed newsFeed) {
        this.exchange = exchange;
        this.newsFeed = newsFeed;
    }

    @GetMapping("/symbols")
    public Set<String> symbols() {
        return exchange.symbols();
    }

    /** The watchlist: every symbol with its name, sector, last price, range, volume and whether it is halted. */
    @GetMapping("/market")
    public List<MarketSummary> market() {
        return exchange.market();
    }

    @GetMapping("/news")
    public List<NewsFeed.Item> news(@RequestParam(defaultValue = "20") int limit) {
        return newsFeed.latest(limit);
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
