import { useMemo, useState } from "react";
import { bookTable, tradesTable } from "./perspective.js";
import { BOOK_DEPTH, useMarketData } from "./useMarketData.js";
import Viewer from "./Viewer.jsx";
import OrderTicket from "./OrderTicket.jsx";

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

function SymbolTab({ symbol, stat, active, onClick }) {
    const change = stat ? ((stat.last - stat.open) / stat.open) * 100 : 0;
    return (
        <button className={active ? "tab active" : "tab"} onClick={onClick}>
            <span className="sym">{symbol}</span>
            <span className="px">{stat ? `£${stat.last.toFixed(2)}` : "—"}</span>
            {stat && <span className={change >= 0 ? "chg up" : "chg down"}>
                {change >= 0 ? "+" : ""}{change.toFixed(2)}%
            </span>}
        </button>
    );
}

export default function App() {
    const { status, symbols, stats } = useMarketData();
    const [picked, setPicked] = useState(null);
    const symbol = picked ?? symbols[0] ?? "MANN";

    const book = useMemo(() => bookConfig(symbol), [symbol]);
    const chart = useMemo(() => chartConfig(symbol), [symbol]);
    const tape = useMemo(() => tapeConfig(symbol), [symbol]);

    return (
        <div className="app">
            <header>
                <div className="brand">Clyde Exchange</div>
                <nav>
                    {symbols.map((s) => (
                        <SymbolTab key={s} symbol={s} stat={stats[s]} active={s === symbol}
                                   onClick={() => setPicked(s)} />
                    ))}
                </nav>
                <div className={`status ${status}`}>
                    <span className="dot" /> {status}
                    {stats[symbol] && <span className="count"> · {stats[symbol].count} trades</span>}
                </div>
            </header>
            <main>
                <section className="left">
                    <div className="panel book" style={{ "--rows": BOOK_DEPTH }}>
                        <Viewer table={bookTable} config={book} />
                    </div>
                    <div className="panel">
                        <OrderTicket symbol={symbol} />
                    </div>
                </section>
                <section className="panel chart">
                    <Viewer table={tradesTable} config={chart} />
                </section>
                <section className="panel tape">
                    <Viewer table={tradesTable} config={tape} />
                </section>
            </main>
        </div>
    );
}
