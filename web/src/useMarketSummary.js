import { useEffect, useState } from "react";

/**
 * Polls GET /api/market (one summary row per symbol) and GET /api/news (latest headlines) once a second.
 * Both are small, so polling is simpler than pushing them over the WebSocket.
 * Returns empty lists until the first answer arrives.
 */
export function useMarketSummary() {
    const [market, setMarket] = useState([]);
    const [news, setNews] = useState([]);

    useEffect(() => {
        let stopped = false;
        async function load() {
            try {
                const [marketRes, newsRes] = await Promise.all([fetch("/api/market"), fetch("/api/news?limit=30")]);
                if (stopped || !marketRes.ok || !newsRes.ok) return;
                setMarket(await marketRes.json());
                setNews(await newsRes.json());
            } catch {
                // server is down: keep showing the last known values; the status dot says "reconnecting"
            }
        }
        load();
        const timer = setInterval(load, 1_000);
        return () => {
            stopped = true;
            clearInterval(timer);
        };
    }, []);

    return { market, news };
}

/**
 * Percent change of the last price today: measured from yesterday's close, like a real exchange.
 * On the market's very first day there is no yesterday, so it is measured from the first trade instead.
 */
export function changePercent(row) {
    const from = row.previousClose || row.open;
    return from > 0 ? ((row.last - from) / from) * 100 : 0;
}
