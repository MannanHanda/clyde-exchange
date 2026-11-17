import { useEffect, useState } from "react";

/**
 * Polls GET /api/market (one summary row per symbol) and GET /api/news (latest headlines) once a second.
 * Both are small, so polling is simpler than pushing them over the WebSocket.
 * Returns empty lists until the first answer arrives.
 */
export function useMarket() {
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

/** Percent change of the last price from the first trade since the server started. 0 before anything trades. */
export function changePercent(row) {
    return row.open > 0 ? ((row.last - row.open) / row.open) * 100 : 0;
}
