# Running Clyde Exchange around the clock

The exchange saves its state to a database once a second, so it can be stopped, updated or crash and carry on where it was. This page shows how to run it as a pair of Docker containers: the exchange, and a PostgreSQL database.

## On your own machine

You need [Docker Desktop](https://www.docker.com/products/docker-desktop/) running.

```bash
docker compose up --build -d      # build the image and start both containers in the background
docker compose logs -f exchange   # watch the server's log (Ctrl+C stops watching, not the server)
```

Open <http://localhost:8080>. The dashboard is served by the same server as the API, so there is nothing else to start.

```bash
docker compose restart exchange   # restart: prices, your cash, positions and open orders are all still there
docker compose down               # stop everything; the database volume is kept
docker compose down -v            # stop everything AND delete the database: the market starts afresh
```

## What happens on a restart

| Kept | Not kept |
|---|---|
| Every account's cash, positions and realised profit | The bots' open orders (they place new ones within a second) |
| Users, login sessions and API keys (saved the moment they are made) | How many wrong passwords each username has had, and the order rate limit counts |
| Traders' open orders, with the same ids | Individual trades between bots (the candles summarise them) |
| Traders' fills | |
| Each symbol's last price, today's open/high/low/volume, yesterday's close | |
| Candles: 1-minute for two days, 1-hour for ever | |
| News headlines (the latest 500) | |

Saving runs on its own thread, once a second, in one database transaction. If the server is killed without warning, at most the last second is lost, and what is in the database is always one consistent snapshot. When it is stopped normally it does one final save first.

## On a server in the cloud

Any Linux machine with Docker works: a small virtual machine with 1 GB of memory is enough.

```bash
git clone https://github.com/MannanHanda/clyde-exchange.git
cd clyde-exchange
echo "POSTGRES_PASSWORD=choose-a-long-random-password" > .env
docker compose up --build -d
```

`restart: unless-stopped` in `docker-compose.yml` brings both containers back after a crash or a reboot.

**Before you put it on the public internet:** the exchange has logins, but it speaks plain HTTP, so passwords and the login cookie would cross the internet unencrypted. Put a reverse proxy that adds HTTPS in front of it. With [Caddy](https://caddyserver.com/) that is one line in a `Caddyfile`, and Caddy gets the certificate by itself:

```
exchange.example.com {
    reverse_proxy localhost:8080
}
```

The server believes the proxy's `X-Forwarded-Proto` header (`server.forward-headers-strategy: native`), so it knows the browser is on HTTPS and marks the login cookie `Secure`. Then close port 8080 to the outside world so that everyone goes through the proxy.

Anyone who can reach the site can register. To stop that, set `EXCHANGE_AUTH_SIGNUPENABLED=false` once your own account exists; existing users can still log in.

## Settings

| Environment variable | What it does | Default |
|---|---|---|
| `SPRING_DATASOURCE_URL` | Where to save. `jdbc:postgresql://host:5432/name` for PostgreSQL. | `jdbc:h2:file:./data/clyde` (a local file) |
| `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | Database login | none |
| `SERVER_PORT` | Port the server listens on | `8080` |
| `EXCHANGE_AUTH_SIGNUPENABLED` | `false` stops new people registering | `true` |
| `EXCHANGE_AUTH_SIGNUPCASH` | What a new trader starts with, in pence | `10000000` (£100,000) |
| `EXCHANGE_AUTH_MAXUSERS` | Registration closes by itself at this many users | `1000` |
| `EXCHANGE_API_ORDERSPERSECOND` | Most orders and cancels one account may send in a second | `20` |
| `JAVA_TOOL_OPTIONS` | Java memory settings | `-XX:MaxRAMPercentage=75` |

The tables are created, and upgraded in later versions, by [Flyway](https://flywaydb.org/) from the scripts in `server/src/main/resources/db/migration` each time the server starts.

`GET /actuator/health` answers `{"status":"UP"}` while the server and its database connection are healthy. Docker uses it for the container's health check.

## Why it can run for months

- Memory is bounded: the server keeps at most 1,440 one-minute and 720 one-hour candles per symbol, the last 100 fills per account, and a trader can have at most 200 open orders.
- The database is pruned every hour: 1-minute candles older than two days and all but the latest 500 headlines are deleted.
- A one-hour simulation in the tests checks that the bots do not pile up open orders.
