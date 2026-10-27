import { useState } from "react";

/** Sends a real order to the exchange over REST, so you can trade against the bots. */
export default function OrderTicket({ symbol }) {
    const [side, setSide] = useState("BUY");
    const [type, setType] = useState("MARKET");
    const [price, setPrice] = useState("");
    const [qty, setQty] = useState("25");
    const [result, setResult] = useState(null);

    async function submit(e) {
        e.preventDefault();
        const body = { symbol, side, type, qty: Number(qty) };
        if (type === "LIMIT") body.price = Math.round(Number(price) * 100);   // pounds -> ticks

        const res = await fetch("/api/orders", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body),
        });
        const json = await res.json();
        if (!res.ok) {
            setResult({ ok: false, text: json.detail ?? "Order rejected" });
            return;
        }
        const filled = json.trades.reduce((sum, t) => sum + t.qty, 0);
        setResult({
            ok: true,
            text: `Order #${json.orderId}: filled ${filled} of ${qty} in ${json.trades.length} trade(s)`,
        });
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
                {["MARKET", "LIMIT"].map((t) => (
                    <button type="button" key={t} className={type === t ? "on" : ""}
                            onClick={() => setType(t)}>{t}</button>
                ))}
            </div>
            <label>
                Price (£)
                <input type="number" step="0.01" min="0.01" value={price} disabled={type === "MARKET"}
                       placeholder={type === "MARKET" ? "best available" : "e.g. 99.50"}
                       onChange={(e) => setPrice(e.target.value)} required={type === "LIMIT"} />
            </label>
            <label>
                Quantity
                <input type="number" min="1" step="1" value={qty} onChange={(e) => setQty(e.target.value)} required />
            </label>
            <button type="submit" className={`send ${side.toLowerCase()}`}>{side} {symbol}</button>
            {result && <div className={result.ok ? "result ok" : "result err"}>{result.text}</div>}
        </form>
    );
}
