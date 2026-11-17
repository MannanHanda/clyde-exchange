import { useEffect, useMemo, useState } from "react";
import { bookTable, tradesTable } from "./perspective.js";
import { BOOK_DEPTH, useMarketData } from "./useMarketData.js";
import Viewer from "./Viewer.jsx";
import OrderTicket from "./OrderTicket.jsx";
import Portfolio from "./Portfolio.jsx";
import Watchlist from "./Watchlist.jsx";
import News from "./News.jsx";
import { useAccount } from "./useAccount.js";
import { changePercent, useMarket } from "./useMarket.js";

const GREEN = "#26a69a";
const RED = "#ef5350";

function bookConfig(symbol) {
    return {
        plugin: "Datagrid",
        title: "Order book",
        columns: ["bid_qty", "bid", "ask", "ask_qty"],
        filter: [["symbol", "==", symbol]],
        sort: [["level", "asc"]],
        columns_config: {
            // Bars inside the qty cells: a mini depth chart for free.
            bid_qty: { number_fg_mode: "bar", pos_fg_color: GREEN, fg_gradient: 250 },
            ask_qty: { number_fg_mode: "bar", pos_fg_color: RED, fg_gradient: 250 },
            bid: { number_fg_mode: "color", pos_fg_color: GREEN },
            ask: { number_fg_mode: "color", pos_fg_color: RED },
        },
    };
}

function chartConfig(symbol) {
    return {
        plugin: "Y Line",
        title: `${symbol} price (last trade per second)`,
        expressions: { second: `bucket("time", '1s')` },
        group_by: ["second"],
        columns: ["price"],
        aggregates: { price: "last" },
        filter: [["symbol", "==", symbol]],
    };
}

function tapeConfig(symbol) {
    return {
        plugin: "Datagrid",
        title: "Trades",
        columns: ["time", "side", "price", "qty"],
        filter: [["symbol", "==", symbol]],
        sort: [["time", "desc"]],
        columns_config: { time: { date_format: { dateStyle: "disabled", timeStyle: "medium" } } },
    };
}

const price = (ticks) => `£${(ticks / 100).toFixed(2)}`;

/** The selected symbol in the header: name, price, change, and the range and volume since the server started. */
function Quote({ row }) {
    const [now, setNow] = useState(Date.now());
    useEffect(() => {
        const timer = setInterval(() => setNow(Date.now()), 1_000);
        return () => clearInterval(timer);
    }, []);

    if (!row) return <div className="quote" />;
    const change = changePercent(row);
    const secondsLeft = Math.max(1, Math.ceil((row.haltedUntil - now) / 1000));
    return (
        <div className="quote">
            <span className="sym">{row.symbol}</span>
            <span className="name">{row.name} · {row.sector}</span>
            <span className="px">{price(row.last)}</span>
            <span className={change >= 0 ? "chg up" : "chg down"}>{change >= 0 ? "+" : ""}{change.toFixed(2)}%</span>
            {row.open > 0 && (
                <span className="range">
                    Low {price(row.low)} · High {price(row.high)} · Volume {row.volume.toLocaleString("en-GB")}
                </span>
            )}
            {row.halted && <span className="halted">TRADING HALTED · resumes in {secondsLeft}s</span>}
        </div>
    );
}

export default function App() {
    const { status, symbols } = useMarketData();
    const { market, news } = useMarket();
    const account = useAccount();
    const [picked, setPicked] = useState(null);
    const symbol = picked ?? symbols[0] ?? "MANN";
    const row = market.find((r) => r.symbol === symbol);

    const book = useMemo(() => bookConfig(symbol), [symbol]);
    const chart = useMemo(() => chartConfig(symbol), [symbol]);
    const tape = useMemo(() => tapeConfig(symbol), [symbol]);

    return (
        <div className="app">
            <header>
                <div className="brand">Clyde Exchange</div>
                <Quote row={row} />
                <div className={`status ${status}`}>
                    <span className="dot" /> {status}
                </div>
            </header>
            <main>
                <section className="side">
                    <div className="panel">
                        <Watchlist market={market} selected={symbol} onSelect={setPicked} />
                    </div>
                    <div className="panel">
                        <News news={news} onSelect={setPicked} />
                    </div>
                </section>
                <section className="left">
                    <div className="panel book" style={{ "--rows": BOOK_DEPTH }}>
                        <Viewer table={bookTable} config={book} />
                    </div>
                    <div className="panel">
                        <OrderTicket symbol={symbol} halted={row?.halted ?? false} />
                    </div>
                </section>
                <section className="centre">
                    <div className="panel chart">
                        <Viewer table={tradesTable} config={chart} />
                    </div>
                    <div className="panel">
                        <Portfolio account={account} onSelect={setPicked} />
                    </div>
                </section>
                <section className="panel tape">
                    <Viewer table={tradesTable} config={tape} />
                </section>
            </main>
        </div>
    );
}
