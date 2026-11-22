import { useState } from "react";

const TYPES = [
    { id: "MARKET", label: "Market", hint: "Trade now, at the best price available." },
    { id: "LIMIT", label: "Limit", hint: "Trade at your price or better. Waits on the book if it can't yet." },
    { id: "STOP", label: "Stop", hint: "Waits until the price reaches your stop, then sells at market. A stop-loss." },
    { id: "STOP_LIMIT", label: "Stop limit", hint: "Waits until the price reaches your stop, then becomes a limit order." },
];
const toTicks = (pounds) => Math.round(Number(pounds) * 100);

/**
 * Sends a real order to the exchange over REST, so you can trade against the bots.
 * The order does not say whose it is: the server knows that from the login cookie.
 */
export default function OrderTicket({ symbol, halted, onLoggedOut }) {
    const [side, setSide] = useState("BUY");
    const [type, setType] = useState("MARKET");
    const [price, setPrice] = useState("");
    const [stopPrice, setStopPrice] = useState("");
    const [qty, setQty] = useState("25");
    const [result, setResult] = useState(null);

    const needsPrice = type === "LIMIT" || type === "STOP_LIMIT";
    const needsStop = type === "STOP" || type === "STOP_LIMIT";

    async function submit(e) {
        e.preventDefault();
        const body = { symbol, side, type, qty: Number(qty) };
        if (needsPrice) body.price = toTicks(price);
        if (needsStop) body.stopPrice = toTicks(stopPrice);

        const res = await fetch("/api/orders", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body),
        });
        const json = await res.json();
        if (res.status === 401) {
            onLoggedOut();   // the session ran out: App shows the login form again
            return;
        }
        if (!res.ok) {
            setResult({ ok: false, text: json.detail ?? "Order rejected" });
            return;
        }
        if (needsStop) {
            setResult({ ok: true, text: `Stop order #${json.orderId} is waiting. It triggers at £${Number(stopPrice).toFixed(2)}.` });
            return;
        }
        const filled = json.trades.reduce((sum, t) => sum + t.qty, 0);
        const cancelled = Number(qty) - filled - json.restingQty;
        let text = `Order #${json.orderId}: filled ${filled} of ${qty} in ${json.trades.length} trade(s)`;
        if (json.restingQty > 0) text += `, ${json.restingQty} waiting on the book`;
        if (cancelled > 0) text += `, ${cancelled} cancelled`;
        setResult({ ok: true, text });
    }

    return (
        <form className="ticket" onSubmit={submit}>
            <div className="ticket-title">Place an order · {symbol}</div>
            <div className="toggle">
                {["BUY", "SELL"].map((s) => (
                    <button type="button" key={s} className={side === s ? `on ${s.toLowerCase()}` : ""}
                            onClick={() => setSide(s)}>{s}</button>
                ))}
            </div>
            <div className="toggle">
                {TYPES.map((t) => (
                    <button type="button" key={t.id} className={type === t.id ? "on" : ""}
                            onClick={() => setType(t.id)}>{t.label}</button>
                ))}
            </div>
            <div className="hint">{TYPES.find((t) => t.id === type).hint}</div>
            {needsStop && (
                <label>
                    Stop price (£): {side === "SELL" ? "below" : "above"} the last price
                    <input type="number" step="0.01" min="0.01" value={stopPrice} placeholder="e.g. 95.00"
                           onChange={(e) => setStopPrice(e.target.value)} required />
                </label>
            )}
            <label>
                {needsStop ? "Limit price (£)" : "Price (£)"}
                <input type="number" step="0.01" min="0.01" value={needsPrice ? price : ""} disabled={!needsPrice}
                       placeholder={needsPrice ? "e.g. 99.50" : "best available"}
                       onChange={(e) => setPrice(e.target.value)} required={needsPrice} />
            </label>
            <label>
                Quantity
                <input type="number" min="1" step="1" value={qty} onChange={(e) => setQty(e.target.value)} required />
            </label>
            <button type="submit" className={`send ${side.toLowerCase()}`} disabled={halted}>
                {halted ? `${symbol} is halted` : `${side} ${symbol}`}
            </button>
            {result && <div className={result.ok ? "result ok" : "result err"}>{result.text}</div>}
        </form>
    );
}
