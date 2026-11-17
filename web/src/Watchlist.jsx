import { changePercent } from "./useMarket.js";

/** Every symbol, grouped by sector, with its last price and change. Click one to trade it. */
export default function Watchlist({ market, selected, onSelect }) {
    const sectors = [...new Set(market.map((row) => row.sector))];

    return (
        <div className="watchlist">
            {sectors.map((sector) => (
                <div key={sector}>
                    <h3>{sector}</h3>
                    {market.filter((row) => row.sector === sector).map((row) => {
                        const change = changePercent(row);
                        return (
                            <button key={row.symbol} title={row.name}
                                    className={row.symbol === selected ? "watch active" : "watch"}
                                    onClick={() => onSelect(row.symbol)}>
                                <span className="sym">{row.symbol}</span>
                                {row.halted
                                    ? <span className="halted">HALTED</span>
                                    : <span className="name">{row.name}</span>}
                                <span className="px">{(row.last / 100).toFixed(2)}</span>
                                <span className={change >= 0 ? "chg up" : "chg down"}>
                                    {change >= 0 ? "+" : ""}{change.toFixed(2)}%
                                </span>
                            </button>
                        );
                    })}
                </div>
            ))}
        </div>
    );
}
