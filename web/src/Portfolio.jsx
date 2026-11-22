import { useState } from "react";
import ApiKeys from "./ApiKeys.jsx";
import Leaderboard from "./Leaderboard.jsx";

const money = new Intl.NumberFormat("en-GB", { style: "currency", currency: "GBP" });
const pounds = (ticks) => money.format(ticks / 100);
const signed = (ticks) => (ticks > 0 ? "+" : "") + pounds(ticks);
const tone = (n) => (n > 0 ? "up" : n < 0 ? "down" : "");
const TYPE_NAMES = { MARKET: "Market", LIMIT: "Limit", STOP: "Stop", STOP_LIMIT: "Stop limit" };

function Stat({ label, value, className = "" }) {
    return (
        <div className="stat">
            <div className="stat-label">{label}</div>
            <div className={`stat-value ${className}`}>{value}</div>
        </div>
    );
}

async function cancel(order) {
    await fetch(`/api/orders/${order.symbol}/${order.orderId}`, { method: "DELETE" });
}

function Positions({ positions, onSelect }) {
    if (positions.length === 0) return <p className="none">You don't hold any shares.</p>;
    return (
        <table>
            <thead><tr><th>Symbol</th><th>Shares</th><th>Avg price</th><th>Last</th><th>Value</th><th>P/L</th></tr></thead>
            <tbody>
                {positions.map((p) => (
                    <tr key={p.symbol} className="clickable" onClick={() => onSelect(p.symbol)}>
                        <td>{p.symbol}</td>
                        <td>{p.qty}</td>
                        <td>{pounds(p.averagePrice)}</td>
                        <td>{pounds(p.lastPrice)}</td>
                        <td>{pounds(p.marketValue)}</td>
                        <td className={tone(p.unrealisedPnl)}>{signed(p.unrealisedPnl)}</td>
                    </tr>
                ))}
            </tbody>
        </table>
    );
}

function OpenOrders({ orders }) {
    if (orders.length === 0) return <p className="none">No orders waiting.</p>;
    return (
        <table>
            <thead><tr><th>#</th><th>Symbol</th><th>Side</th><th>Type</th><th>Stop</th><th>Price</th><th>Left</th><th /></tr></thead>
            <tbody>
                {orders.map((o) => (
                    <tr key={o.orderId}>
                        <td>{o.orderId}</td>
                        <td>{o.symbol}</td>
                        <td className={o.side === "BUY" ? "up" : "down"}>{o.side}</td>
                        <td>{TYPE_NAMES[o.type] ?? o.type}</td>
                        <td>{o.stopPrice > 0 ? pounds(o.stopPrice) : "—"}</td>
                        <td>{o.type === "STOP" ? "market" : pounds(o.price)}</td>
                        <td>{o.remaining} / {o.quantity}</td>
                        <td><button className="cancel" onClick={() => cancel(o)}>Cancel</button></td>
                    </tr>
                ))}
            </tbody>
        </table>
    );
}

function Fills({ fills }) {
    if (fills.length === 0) return <p className="none">Nothing traded yet.</p>;
    return (
        <table>
            <thead><tr><th>Time</th><th>Symbol</th><th>Side</th><th>Price</th><th>Qty</th><th>Value</th></tr></thead>
            <tbody>
                {fills.slice(0, 50).map((f, i) => (
                    <tr key={`${f.orderId}-${i}`}>
                        <td>{new Date(f.timestamp).toLocaleTimeString()}</td>
                        <td>{f.symbol}</td>
                        <td className={f.side === "BUY" ? "up" : "down"}>{f.side}</td>
                        <td>{pounds(f.price)}</td>
                        <td>{f.qty}</td>
                        <td>{pounds(f.price * f.qty)}</td>
                    </tr>
                ))}
            </tbody>
        </table>
    );
}

/**
 * Your money, your shares and your waiting orders, plus your API keys and the leaderboard.
 * `account` comes from useAccount(). With nobody logged in, only the leaderboard is shown.
 */
export default function Portfolio({ account, user, onSelect }) {
    const [tab, setTab] = useState("positions");
    if (!user) {
        return (
            <div className="portfolio">
                <div className="subtabs"><button className="on">Leaderboard</button></div>
                <div className="tables"><Leaderboard user={null} /></div>
            </div>
        );
    }
    if (!account) return <div className="portfolio empty">Loading {user}’s account…</div>;

    const pnl = account.realisedPnl + account.unrealisedPnl;
    const tabs = [
        { id: "positions", label: `Positions (${account.positions.length})` },
        { id: "orders", label: `Open orders (${account.openOrders.length})` },
        { id: "fills", label: "Recent fills" },
        { id: "keys", label: "API keys" },
        { id: "leaderboard", label: "Leaderboard" },
    ];
    return (
        <div className="portfolio">
            <div className="stats">
                <Stat label="Account value" value={pounds(account.equity)} />
                <Stat label="Cash" value={pounds(account.cash)} />
                <Stat label="Buying power" value={pounds(account.buyingPower)} />
                <Stat label="Profit / loss" value={signed(pnl)} className={tone(pnl)} />
            </div>

            <div className="subtabs">
                {tabs.map((t) => (
                    <button key={t.id} className={tab === t.id ? "on" : ""} onClick={() => setTab(t.id)}>{t.label}</button>
                ))}
            </div>
            <div className="tables">
                {tab === "positions" && <Positions positions={account.positions} onSelect={onSelect} />}
                {tab === "orders" && <OpenOrders orders={account.openOrders} />}
                {tab === "fills" && <Fills fills={account.fills} />}
                {tab === "keys" && <ApiKeys />}
                {tab === "leaderboard" && <Leaderboard user={user} />}
            </div>
        </div>
    );
}
