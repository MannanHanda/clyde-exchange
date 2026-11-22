"""
A small trading bot for Clyde Exchange, to copy and change. Python 3.9+, nothing to install.

It watches one symbol and trades a simple mean-reversion idea:
  - keep the average of the last 20 prices it has seen
  - buy when the price drops 0.2% below that average (it hopes the price comes back up)
  - sell when the price is 0.3% above the price it paid, or 0.5% below it (a stop-loss)

This is an example of how to use the API, not a strategy that makes money.

1. Log in on the dashboard, open the "API keys" tab and create a key.
2. Run:   CLYDE_API_KEY=clyde_... python examples/bot.py
          (Windows PowerShell:  $env:CLYDE_API_KEY="clyde_..."; python examples/bot.py)
   Set CLYDE_URL if the exchange is not on http://localhost:8080, and CLYDE_SYMBOL to trade something other than MANN.
"""

import json
import os
import sys
import time
import urllib.error
import urllib.request
from collections import deque

URL = os.environ.get("CLYDE_URL", "http://localhost:8080").rstrip("/")
KEY = os.environ.get("CLYDE_API_KEY")
SYMBOL = os.environ.get("CLYDE_SYMBOL", "MANN")
QTY = 10              # shares per trade
WINDOW = 20           # prices in the moving average
CHECK_EVERY = 2       # seconds between looks at the market


class ApiError(Exception):
    def __init__(self, status, detail):
        super().__init__(f"{status}: {detail}")
        self.status = status


def call(method, path, body=None):
    """Sends one request with the API key and returns the JSON answer (None for an empty one)."""
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(URL + path, data=data, method=method)
    request.add_header("X-API-Key", KEY)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            text = response.read()
            return json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        # The server explains every refusal in a "detail" field: not enough cash, trading halted, bad key...
        try:
            detail = json.loads(e.read()).get("detail", e.reason)
        except ValueError:
            detail = e.reason
        raise ApiError(e.code, detail) from None


def pounds(ticks):
    return f"£{ticks / 100:,.2f}"


def last_price(symbol):
    for row in call("GET", "/api/market"):
        if row["symbol"] == symbol:
            return row["last"], row["halted"]
    raise SystemExit(f"There is no symbol {symbol}")


def held(account, symbol):
    """Shares of symbol we own and are free to sell, and what we paid for them on average."""
    for position in account["positions"]:
        if position["symbol"] == symbol:
            return position["available"], position["averagePrice"]
    return 0, 0


def market_order(side, qty):
    result = call("POST", "/api/orders", {"symbol": SYMBOL, "side": side, "type": "MARKET", "qty": qty})
    filled = sum(trade["qty"] for trade in result["trades"])
    paid = sum(trade["qty"] * trade["price"] for trade in result["trades"])
    if filled:
        print(f"  {side} {filled} {SYMBOL} at an average of {pounds(paid // filled)} (order #{result['orderId']})")
    else:
        print(f"  {side} {qty} {SYMBOL}: nothing filled, nobody was on the other side")


def main():
    if not KEY:
        sys.exit("Set CLYDE_API_KEY first: create a key in the dashboard's API keys tab.")

    try:
        account = call("GET", "/api/account")
    except ApiError as e:
        sys.exit(f"The exchange refused the key ({e}). Copy it again from the API keys tab.")
    except urllib.error.URLError as e:
        sys.exit(f"Cannot reach {URL}: {e.reason}. Is the exchange running?")
    print(f"Trading {SYMBOL} as {account['accountId']} with {pounds(account['buyingPower'])} to spend. Ctrl+C stops.")

    prices = deque(maxlen=WINDOW)
    while True:
        try:
            price, halted = last_price(SYMBOL)
            prices.append(price)
            account = call("GET", "/api/account")
            qty, paid = held(account, SYMBOL)
            average = sum(prices) / len(prices)

            print(f"{time.strftime('%H:%M:%S')} {SYMBOL} {pounds(price)}  average {pounds(round(average))}  "
                  f"holding {qty}  account value {pounds(account['equity'])}")

            if halted or len(prices) < WINDOW:
                pass   # wait for trading to resume, or for enough prices to trust the average
            elif qty == 0 and price < average * 0.998 and account["buyingPower"] > price * QTY * 2:
                market_order("BUY", QTY)
            elif qty > 0 and (price > paid * 1.003 or price < paid * 0.995):
                market_order("SELL", qty)
        except ApiError as e:
            if e.status == 401:
                sys.exit(f"The exchange refused the key ({e}). Was it deleted?")
            print(f"  refused {e}")   # 400: the order was not allowed; 429: slow down
        except urllib.error.URLError as e:
            print(f"  cannot reach {URL}: {e.reason}. Trying again...")
        time.sleep(CHECK_EVERY)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nStopped. Any shares the bot bought are still in your account.")
