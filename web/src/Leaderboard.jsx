import { useEffect, useState } from "react";

const money = new Intl.NumberFormat("en-GB", { style: "currency", currency: "GBP" });

/**
 * Every registered trader ranked by return: what their account is worth now against what they started with.
 * Public, so it shows even when nobody is logged in. `user` is highlighted if they are on it.
 */
export default function Leaderboard({ user }) {
    const [rows, setRows] = useState(null);

    useEffect(() => {
        let stopped = false;
        async function load() {
            try {
                const res = await fetch("/api/leaderboard?limit=50");
                if (!stopped && res.ok) setRows(await res.json());
            } catch {
                // the server is down: keep showing the last answer
            }
        }
        load();
        const timer = setInterval(load, 5_000);
        return () => {
            stopped = true;
            clearInterval(timer);
        };
    }, []);

    if (!rows) return <p className="none">Loading…</p>;
    if (rows.length === 0) return <p className="none">Nobody has registered yet. Be the first.</p>;
    return (
        <table>
            <thead><tr><th>#</th><th>Trader</th><th>Account value</th><th>Started with</th><th>Return</th></tr></thead>
            <tbody>
                {rows.map((row) => {
                    const shown = Number(row.returnPercent.toFixed(2)) || 0;   // so a loss of 0.001% reads 0.00%, not -0.00%
                    return (
                        <tr key={row.username} className={row.username === user ? "me" : ""}>
                            <td>{row.rank}</td>
                            <td>{row.username}{row.username === user && " (you)"}</td>
                            <td>{money.format(row.equity / 100)}</td>
                            <td>{money.format(row.startingCash / 100)}</td>
                            <td className={shown > 0 ? "up" : shown < 0 ? "down" : ""}>
                                {shown > 0 ? "+" : ""}{shown.toFixed(2)}%
                            </td>
                        </tr>
                    );
                })}
            </tbody>
        </table>
    );
}
