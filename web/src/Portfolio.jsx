import { ACCOUNT } from "./useAccount.js";

const money = new Intl.NumberFormat("en-GB", { style: "currency", currency: "GBP" });
const pounds = (ticks) => money.format(ticks / 100);
const signed = (ticks) => (ticks > 0 ? "+" : "") + pounds(ticks);
const tone = (n) => (n > 0 ? "up" : n < 0 ? "down" : "");

function Stat({ label, value, className = "" }) {
    return (
        <div className="stat">
            <div className="stat-label">{label}</div>
            <div className={`stat-value ${className}`}>{value}</div>
        </div>
    );
}

async function cancel(order) {
    await fetch(`/api/orders/${order.symbol}/${order.orderId}?accountId=${ACCOUNT}`, { method: "DELETE" });
}

/** Your money, your shares and your waiting orders. `account` comes from useAccount(). */
export default function Portfolio({ account }) {
    if (!account) return <div className="portfolio empty">No account data for “{ACCOUNT}” yet…</div>;

    const pnl = account.realisedPnl + account.unrealisedPnl;
    return (
        <div className="portfolio">
            <div className="stats">
                <Stat label="Account value" value={pounds(account.equity)} />
                <Stat label="Cash" value={pounds(account.cash)} />
                <Stat label="Buying power" value={pounds(account.buyingPower)} />
                <Stat label="Profit / loss" value={signed(pnl)} className={tone(pnl)} />
            </div>

            <div className="tables">
                <div>
                    <h3>Positions</h3>
                    {account.positions.length === 0 ? <p className="none">You don't hold any shares.</p> : (
                        <table>
                            <thead><tr><th>Symbol</th><th>Shares</th><th>Avg price</th><th>Last</th><th>Value</th><th>P/L</th></tr></thead>
                            <tbody>
                                {account.positions.map((p) => (
                                    <tr key={p.symbol}>
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
                    )}
                </div>
                <div>
                    <h3>Open orders</h3>
                    {account.openOrders.length === 0 ? <p className="none">No orders waiting.</p> : (
                        <table>
                            <thead><tr><th>#</th><th>Symbol</th><th>Side</th><th>Price</th><th>Left</th><th /></tr></thead>
                            <tbody>
                                {account.openOrders.map((o) => (
                                    <tr key={o.orderId}>
                                        <td>{o.orderId}</td>
                                        <td>{o.symbol}</td>
                                        <td className={o.side === "BUY" ? "up" : "down"}>{o.side}</td>
                                        <td>{pounds(o.price)}</td>
                                        <td>{o.remaining} / {o.quantity}</td>
                                        <td><button className="cancel" onClick={() => cancel(o)}>Cancel</button></td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    )}
                </div>
                <div>
                    <h3>Your recent fills</h3>
                    {account.fills.length === 0 ? <p className="none">Nothing traded yet.</p> : (
                        <table>
                            <thead><tr><th>Time</th><th>Symbol</th><th>Side</th><th>Price</th><th>Qty</th></tr></thead>
                            <tbody>
                                {account.fills.slice(0, 20).map((f, i) => (
                                    <tr key={`${f.orderId}-${i}`}>
                                        <td>{new Date(f.timestamp).toLocaleTimeString()}</td>
                                        <td>{f.symbol}</td>
                                        <td className={f.side === "BUY" ? "up" : "down"}>{f.side}</td>
                                        <td>{pounds(f.price)}</td>
                                        <td>{f.qty}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    )}
                </div>
            </div>
        </div>
    );
}
