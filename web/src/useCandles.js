import { useEffect } from "react";
import { candlesTable } from "./perspective.js";

export const INTERVALS = ["1m", "5m", "15m", "1h", "4h", "1d"];
const toPounds = (ticks) => ticks / 100;

/**
 * Keeps the candles of one symbol and interval up to date in the Perspective candles table.
 * GET /api/candles/{symbol} is asked again every two seconds: the newest candle is still being built,
 * and because the table is indexed by key, each answer overwrites the rows it already has.
 * Pass a null interval to switch it off (the live chart does not need candles).
 */
export function useCandles(symbol, interval) {
    useEffect(() => {
        if (!interval) return;
        let stopped = false;
        async function load() {
            try {
                const res = await fetch(`/api/candles/${symbol}?interval=${interval}&limit=300`);
                if (stopped || !res.ok) return;
                const candles = await res.json();
                candlesTable.update(candles.map((c) => ({
                    key: `${symbol}:${interval}:${c.start}`,
                    symbol,
                    interval,
                    time: c.start,
                    open: toPounds(c.open),
                    close: toPounds(c.close),
                    high: toPounds(c.high),
                    low: toPounds(c.low),
                    volume: c.volume,
                })));
            } catch {
                // server is down: keep the candles we have
            }
        }
        load();
        const timer = setInterval(load, 2_000);
        return () => {
            stopped = true;
            clearInterval(timer);
        };
    }, [symbol, interval]);
}
