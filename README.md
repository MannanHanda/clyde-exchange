# Clyde Exchange

[![CI](https://github.com/MannanHanda/clyde-exchange/actions/workflows/ci.yml/badge.svg)](https://github.com/MannanHanda/clyde-exchange/actions/workflows/ci.yml)

A stock exchange simulator written in Java 21. At its core is a **price-time priority matching engine**: the same basic mechanism real exchanges use to decide who trades with whom, at what price, and in what order.

Ten made-up companies in five sectors trade around the clock. Bots with different strategies trade against each other, news stories move prices, and a circuit breaker halts a share that moves too fast. A live dashboard built with [Perspective](https://perspective.finos.org/) (the streaming analytics library open-sourced by J.P. Morgan) shows the watchlist, news, order book, price chart and trade tape. You get a paper-trading account with £100,000 of pretend money and can trade against the bots from the browser, with your cash, positions and profit tracked live.

![Clyde Exchange live demo: bots trading, then a 300-share market buy sweeping the ask side](docs/demo.gif)

---

## Architecture

```mermaid
flowchart LR
    subgraph web["web/ (React + Perspective)"]
        UI["Watchlist · News · Order book<br/>Price chart · Trade tape · Portfolio"]
    end

    subgraph server["server/ (Spring Boot)"]
        REST["REST API<br/>/api/orders, /api/market, /api/news, /api/accounts"]
        WS["WebSocket<br/>/ws/market"]
        BOTS["Simulator<br/>price model, news, 5 kinds of bot"]
        SVC["ExchangeService<br/>single writer thread<br/>risk check, accounts, circuit breaker"]
    end

    subgraph engine["engine/ (plain Java, no frameworks)"]
        ME["MatchingEngine<br/>one OrderBook per symbol"]
        OB["OrderBook<br/>bids + asks"]
        PL["PriceLevel<br/>FIFO queue of Orders"]
    end

    UI -- "place / cancel" --> REST
    WS -- "trades + book snapshots" --> UI
    REST --> SVC
    BOTS --> SVC
    SVC --> ME
    ME --> OB --> PL
    SVC -- "engine events" --> WS
```

### Why it's split this way

- **The `engine` module has no dependencies at all**, apart from JUnit for tests. Only the `server` module depends on Spring. The matching logic is pure Java. Given the same inputs, it always produces the same outputs. That makes it easy to test, and it can later be benchmarked or placed behind a message queue without changing it.
- **Single writer.** Only one thread ever touches the order book. REST requests and bots hand their orders to `ExchangeService`, which processes them one at a time. This avoids locks and race conditions completely. The approach is a small version of the "single writer principle" used in the LMAX exchange architecture.

---

## Matching rules

- **Price-time priority.** The best price trades first. At the same price, whichever order arrived first trades first (FIFO).
- **Limit orders** trade while they *cross* the book: a buy matches when its price ≥ the best ask, and a sell matches when its price ≤ the best bid. Whatever is left rests on the book.
- **Trades execute at the resting order's price.** The order that was already waiting sets the price.
- **Market orders** sweep the opposite side until filled. Any leftover is cancelled, never rested. A market order into an empty book fills nothing and leaves no trace.
- **Self-trade prevention.** Every order belongs to an account, and an account can never trade with itself. If an incoming order reaches one of its own resting orders, it stops there and its remainder is cancelled (the "cancel newest" policy).
- **Stop orders** wait *off* the book, where nobody can see or trade with them. A sell stop triggers when the last trade price falls to its stop price or below; a buy stop triggers when it rises to its stop price or above. When it triggers, a `STOP` becomes a market order and a `STOP_LIMIT` becomes a limit order. A stop that would trigger straight away is rejected.
- **Stop cascades.** A triggered stop can trade, move the price and trigger more stops. The engine keeps going until no more stops trigger, oldest first, all inside the same call.
- **Validation:** quantity must be > 0. A fill can never exceed an order's remaining quantity.

## Data structures

| What | Choice | Why |
|---|---|---|
| Prices | `long` in ticks (£101.25 → `10125`) | `double` can't represent values like 0.1 exactly, so equality checks on prices would break. Real exchanges use integer ticks. |
| Orders at one price | `ArrayDeque<Order>` inside `PriceLevel` | Gives FIFO order for free, so arrival order *is* the time priority. |
| Total quantity at a price | cached `totalQty` field | Quantity at a price can be read instantly, in O(1). Every fill goes through `PriceLevel`, so the cached total can't drift out of date. |
| Price levels | `TreeMap<Long, PriceLevel>`; bids use `Comparator.reverseOrder()` | Kept sorted, so the best price is always the first entry, in O(log n). |
| Cancel lookup | `HashMap<orderId, Order>` | O(1) lookup by id. |

**Alternatives considered**
- `PriorityQueue<Order>`: no grouping by price level, cancels are O(n), and ties need a separate comparator.
- An array indexed by price tick: O(1) for everything, and the approach high-frequency engines use, but it needs a fixed price range.

## Accounts and paper trading

Every order carries an `accountId`, and every trade moves cash from the buyer to the seller and shares the other way.

- **Pre-trade risk check.** Before an order reaches the engine, the server checks the account can cover it in the worst case. There is no borrowing: you can't buy with money you don't have or sell shares you don't hold. A market buy is checked against what it would really cost by walking the ask side of the book.
- **Reserved funds.** Cash promised to resting buy orders, and shares promised to resting sell orders, can't be used twice. `buyingPower = cash - reservedCash`. Reservations are not stored: they are worked out from the engine's open orders each time, so they can't drift out of step with the book.
- **Why the check is safe.** The check and the match run back to back on the single writer thread, so nothing can change between "can they afford it?" and "do it". No locks needed.
- **Profit and loss.** Positions use the average-cost method. Selling realises profit against the average price paid; what you still hold is valued at the last trade price (unrealised).
- **Stops are reserved too.** A waiting sell stop holds its shares and a waiting buy stop-limit holds its cash, so the order can always be honoured when it triggers. A trader can't place a buy stop *without* a limit, because nobody can say in advance what it would cost.
- **Bots have accounts too.** The market maker and noise traders (`bot-maker`, `bot-noise-1`…) skip the risk check, so they can go short. The value, momentum and investor bots get a fixed pot of cash and shares and pass the same risk check as you, so they can run out of money.
- **Trader accounts** are listed in `application.yml` under `exchange.accounts.starting-cash`. There is no login yet; the dashboard trades as `mannan`.

---

## Circuit breaker

Each symbol has its own circuit breaker, like the "limit up / limit down" rules on real exchanges. If the price moves **5% or more within 60 seconds**, trading in that symbol is **halted for 15 seconds**: new orders are rejected with a message, cancels are still allowed, and the other symbols carry on. A headline goes on the news feed. The numbers are set in `application.yml` under `exchange.circuit-breaker`.

The check runs on the single writer thread right after each trade, so no order can slip in between the trade that trips the breaker and the halt.

---

## Running it

You need JDK 21 and Node 22+. Maven is not required, because the project includes the Maven Wrapper.

```bash
./mvnw test                 # run all tests (Windows: .\mvnw test)
./mvnw -pl engine test      # engine tests only

./mvnw install -DskipTests && ./mvnw -pl server spring-boot:run   # 1. exchange + bots on :8080

cd web && npm install && npm run dev                              # 2. dashboard on http://localhost:5173
```

## REST API

All prices are in **ticks of 1p** (`10100` = £101.00). Symbols are configured in `server/src/main/resources/application.yml`.

| Method | Path | What it does |
|---|---|---|
| `POST` | `/api/orders` | Place an order. Body: `{"accountId","symbol","side","type","price","qty"}`; `type` is `MARKET`, `LIMIT`, `STOP` or `STOP_LIMIT`. `price` is needed for `LIMIT` and `STOP_LIMIT`, and `stopPrice` for the two stop types. Returns the order id, any trades, and `restingQty` (how much is now waiting on the book). |
| `DELETE` | `/api/orders/{symbol}/{id}?accountId=…` | Cancel one of your resting orders: `204`, or `404` if unknown, already filled, or not yours |
| `GET` | `/api/accounts/{id}` | Cash, buying power, positions, profit/loss, open orders and recent fills |
| `GET` | `/api/book/{symbol}?depth=10` | Top price levels on each side, best first |
| `GET` | `/api/trades/{symbol}?limit=50` | Recent trades, newest first |
| `GET` | `/api/market` | One row per symbol: name, sector, last, open, high, low, volume, and whether it is halted |
| `GET` | `/api/news?limit=20` | Latest headlines, newest first |
| `GET` | `/api/symbols` | Tradable symbols |

Invalid or unaffordable orders get `400 Bad Request` with a message, e.g. `{"detail":"Quantity must be > 0, was 0"}` or `{"detail":"Insufficient shares: trying to sell 80 MANN but only 60 available"}`.

```bash
# buy 100 MANN from the bots at the best available prices, then offer 40 of them for sale at £105.00
curl -X POST localhost:8080/api/orders -H 'Content-Type: application/json' \
     -d '{"accountId":"mannan","symbol":"MANN","side":"BUY","type":"MARKET","qty":100}'
curl -X POST localhost:8080/api/orders -H 'Content-Type: application/json' \
     -d '{"accountId":"mannan","symbol":"MANN","side":"SELL","type":"LIMIT","price":10500,"qty":40}'
curl localhost:8080/api/accounts/mannan   # cash down, 100 MANN held, 60 available, one open order

# a stop-loss: sell the other 60 at market if MANN ever trades at £95.00 or below
curl -X POST localhost:8080/api/orders -H 'Content-Type: application/json' \
     -d '{"accountId":"mannan","symbol":"MANN","side":"SELL","type":"STOP","stopPrice":9500,"qty":60}'
```

## Live market data and bots

When the server starts, a simulator trades all ten symbols on its own, four ticks a second (turn it off with `exchange.sim.enabled: false`). The companies and their sectors are listed in `application.yml` under `exchange.instruments`.

**The price model.** Each company has a hidden *fair value*: what it is "really" worth. Nobody trades at it directly; it is what the smarter bots steer by. Every tick it moves by the sum of three random steps:

- a **market** step shared by all ten companies,
- a **sector** step shared by companies in the same sector,
- the company's **own** step.

So shares in the same sector tend to rise and fall together, as they do in real markets. On top of that there are **stormy spells** (every step gets 2.5 times bigger for a while, so calm and wild periods come in clusters), slow **trends** that last a few minutes, a weak pull back towards the starting price, and **news**: now and then a story breaks and a company (or a whole sector) jumps by 1 to 9%.

**The bots.** The traded price is not the fair value. It is whatever the bots' orders make it:

- **Market maker:** always quotes five levels of bids and asks, so there is always someone to trade with. It doesn't know the fair value exactly. It keeps its own reference price, which it nudges when people trade against it, when news breaks, and slowly towards fair value. It widens its spread when the price is moving fast.
- **Noise traders:** buy and sell at random. They create most of the trades.
- **Value trader:** buys when the price is below fair value and sells when it is above. This is what pulls the price back towards what the company is worth.
- **Momentum trader:** compares a fast and a slow moving average of the price, buys when the price is rising and sells out when it falls. It makes trends run further.
- **Investor:** every so often buys or sells a big position in small slices over a minute or two, pushing the price in one direction.

The last three have limited money and pass the same risk check as a human trader. A one-hour headless simulation runs in the tests (`SimulationTest`) and checks the books stay two-sided, the price tracks fair value and no limited bot is ever overdrawn.

Connect to `ws://localhost:8080/ws/market` to receive, at most every 200 ms:

```json
{"type":"trades","trades":[{"symbol":"MANN","buyOrderId":1751,"sellOrderId":1822,"price":9903,"qty":6,"timestamp":1793076835022}]}
{"type":"book","book":{"symbol":"MANN","bids":[{"price":9895,"qty":74}],"asks":[{"price":9903,"qty":180}]}}
```

**Why batched?** The matching thread never touches the network. It drops each trade into a lock-free queue, and a separate timer sends batches. A slow browser can't slow down matching, and the browser gets at most 5 updates a second instead of one per trade.

## Dashboard

`web/` is a small React app built with Vite. Perspective does the heavy lifting: it runs a columnar data engine in WebAssembly inside a Web Worker, so the browser can filter, sort and aggregate thousands of streaming rows without blocking the page.

- **One stream, many views.** `useMarketData` opens the WebSocket (reconnecting with exponential backoff) and writes into two in-browser Perspective tables: `trades` (capped at 60,000 rows) and `book` (indexed by `symbol:level`, so each snapshot overwrites rows in place). Every panel is a `<perspective-viewer>` over one of those tables with a different config. Switching symbol just changes a filter.
- **Watchlist:** every symbol grouped by sector with its last price and change, and a `HALTED` badge while its circuit breaker is tripped. Click a symbol to trade it. It polls `GET /api/market` once a second.
- **News:** the latest headlines. Click a company story to jump to that symbol.
- **Order book:** best 10 levels each side, with in-cell bars showing size at each price.
- **Price chart:** the last trade price per second, using Perspective's `bucket()` expression and a `last` aggregate.
- **Trade tape:** newest first, with the aggressor side. The server doesn't send a side, but order ids only go up, so the newer of the buy and sell ids is the order that arrived and crossed the spread.
- **Order ticket:** sends real `MARKET`, `LIMIT`, `STOP` or `STOP_LIMIT` orders to `POST /api/orders` and reports what filled, what is waiting and what was cancelled. It is disabled while the symbol is halted.
- **Portfolio:** account value, cash, buying power and profit/loss, plus tabs for your positions, open orders (with a cancel button, and including waiting stops) and recent fills. It polls `GET /api/accounts/{id}` once a second.
- On page load it backfills the last 500 trades per symbol over REST, de-duplicating against the live stream.

![Dashboard screenshot](docs/dashboard.png)

## Project layout

```
engine/   matching engine (plain Java)
  model/    Order, Side, OrderType, Trade
  book/     PriceLevel, OrderBook, MatchingEngine, BookSnapshot, OpenOrder
server/   Spring Boot application
  service/  ExchangeService (single writer, risk check, settlement), CircuitBreaker, Instrument, MarketSummary, TradeRecord
  account/  Account, Position (average cost, profit/loss), AccountView
  api/      OrderController, MarketDataController, AccountController, error handling
  news/     NewsFeed (latest headlines)
  ws/       MarketDataPublisher (batched WebSocket feed)
  sim/      PriceModel, Simulator, SimulatorRunner, and the bots: MarketMakerBot, NoiseTraderBot,
            ValueTraderBot, MomentumBot, InvestorBot
web/      React + Vite + Perspective dashboard
  src/perspective.js    starts Perspective (WebAssembly) and creates the tables
  src/useMarketData.js  WebSocket + REST backfill -> tables
  src/useMarket.js      polls the market summary and the news
  src/useAccount.js     polls the account
  src/Watchlist.jsx     symbols by sector
  src/News.jsx          headlines
  src/OrderTicket.jsx   market, limit and stop orders
  src/Portfolio.jsx     cash, positions, open orders, fills
  src/App.jsx           layout and the viewer configs
```

---

## Status

| Part | State |
|---|---|
| `Order`: fill logic with overfill/negative guards | ✅ done, tested |
| `PriceLevel`: FIFO queue + cached total | ✅ done, tested |
| `OrderBook`: bids/asks, limit-order matching | ✅ done, tested |
| Market orders, cancels, `MatchingEngine` (validation, ids, one book per symbol) | ✅ done, tested |
| REST API + single-writer `ExchangeService` (incl. 8-thread concurrency test) | ✅ done, tested |
| WebSocket market data + trading bots (market maker, noise traders, random-walk fair value) | ✅ done, tested |
| Perspective dashboard: order book, price chart, trade tape, order ticket | ✅ done, verified in browser |
| Accounts: order ownership, cash and positions, buying-power check, self-trade prevention, profit/loss | ✅ done, tested |
| Portfolio panel: balances, positions, open orders with cancel, fills | ✅ done, verified in browser |
| Stop and stop-limit orders, with cascades | ✅ done, tested |
| Ten symbols in five sectors; price model with market, sector and company moves, storms, trends and news | ✅ done, tested |
| Value, momentum and investor bots with limited money; one-hour simulation test | ✅ done, tested |
| Per-symbol circuit breaker and news feed | ✅ done, tested |
| Watchlist, news panel, stop orders in the ticket | ✅ done, verified in browser |

## Known limitations

- No login: the API trusts the `accountId` it is given.
- No persistence. The book and all account balances live in memory and are lost on restart.
- No short selling or margin for traders (the market maker and noise traders can go short).
- Trading simply resumes after a halt. Real exchanges reopen with an auction.
- A trader can't place a buy stop without a limit price, and there are no linked orders (such as "one cancels the other").
- "Open" in the market summary means the first trade since the server started, not a daily open. There are no trading days yet.
- Cancelling an order is O(k) in the number of orders at its price level.

## Roadmap

- **Kafka:** turn orders and engine events into topics. The engine is deterministic, so replaying the order log rebuilds the exact same book.
- **JMH benchmarks:** measure matching throughput and latency, and compare `ArrayDeque` against a linked list for fast cancels.
- **LMAX Disruptor:** replace the single-writer queue with a ring buffer, and measure the difference.
