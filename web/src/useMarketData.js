import { useEffect, useState } from "react";
import { bookTable, tradesTable } from "./perspective.js";

export const BOOK_DEPTH = 10;
const toPounds = (ticks) => ticks / 100;

// Order ids only go up, so the newer order of the two is the one that arrived
// and crossed the spread (the aggressor). A buy aggressor means buyers lifted the offer.
const aggressor = (t) => (t.buyOrderId > t.sellOrderId ? "BUY" : "SELL");
const tradeKey = (t) => `${t.buyOrderId}-${t.sellOrderId}`;

function tradeRows(trades) {
    return trades.map((t) => ({
        symbol: t.symbol,
        time: t.timestamp,
        side: aggressor(t),
        price: toPounds(t.price),
        qty: t.qty,
    }));
}

function bookRows({ symbol, bids, asks }) {
    const rows = [];
    for (let i = 0; i < BOOK_DEPTH; i++) {
        // Always write all levels: null clears a level that has disappeared since last time.
        rows.push({
            key: `${symbol}:${i}`,
            symbol,
            level: i + 1,
            bid_qty: bids[i]?.qty ?? null,
            bid: bids[i] ? toPounds(bids[i].price) : null,
            ask: asks[i] ? toPounds(asks[i].price) : null,
            ask_qty: asks[i]?.qty ?? null,
        });
    }
    return rows;
}

/**
 * Loads recent trades over REST, then streams /ws/market into the Perspective tables.
 * Reconnects automatically if the server goes away. Returns the connection status
 * and a per-symbol summary (last price, first price seen, trade count) for the header.
 */
export function useMarketData() {
    const [status, setStatus] = useState("connecting");
    const [symbols, setSymbols] = useState([]);
    const [stats, setStats] = useState({});

    useEffect(() => {
        let socket;
        let retryTimer;
        let retryDelay = 500;
        let stopped = false;
        const seen = new Set();   // trades can arrive via both the REST backfill and the socket

        function addTrades(trades) {
            const fresh = trades.filter((t) => !seen.has(tradeKey(t)));
            if (fresh.length === 0) return;
            fresh.forEach((t) => seen.add(tradeKey(t)));
            if (seen.size > 50_000) seen.clear();
            tradesTable.update(tradeRows(fresh));

            setStats((prev) => {
                const next = { ...prev };
                for (const t of [...fresh].sort((a, b) => a.timestamp - b.timestamp)) {
                    const s = next[t.symbol] ?? { open: toPounds(t.price), count: 0 };
                    next[t.symbol] = { ...s, last: toPounds(t.price), count: s.count + 1 };
                }
                return next;
            });
        }

        async function backfill() {
            const list = await (await fetch("/api/symbols")).json();
            setSymbols(list);
            for (const symbol of list) {
                const res = await fetch(`/api/trades/${symbol}?limit=500`);
                if (res.ok) addTrades(await res.json());
            }
        }

        function connect() {
            const scheme = location.protocol === "https:" ? "wss" : "ws";
            socket = new WebSocket(`${scheme}://${location.host}/ws/market`);

            socket.onopen = () => {
                setStatus("live");
                retryDelay = 500;
                backfill().catch(() => {});
            };
            socket.onmessage = (event) => {
                const msg = JSON.parse(event.data);
                if (msg.type === "trades") addTrades(msg.trades);
                else if (msg.type === "book") bookTable.update(bookRows(msg.book));
            };
            socket.onclose = () => {
                if (stopped) return;
                setStatus("reconnecting");
                retryTimer = setTimeout(connect, retryDelay);
                retryDelay = Math.min(retryDelay * 2, 5_000);   // back off: 0.5s, 1s, 2s, 4s, 5s...
            };
        }

        connect();
        return () => {
            stopped = true;
            clearTimeout(retryTimer);
            socket?.close();
        };
    }, []);

    return { status, symbols, stats };
}
