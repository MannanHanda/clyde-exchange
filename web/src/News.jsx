/** The latest headlines, newest first. Company news can be clicked to jump to that symbol. */
export default function News({ news, onSelect }) {
    return (
        <div className="news">
            <h3>News</h3>
            {news.length === 0 && <p className="none">No news yet. Stories break every few minutes.</p>}
            {news.map((item) => (
                <button key={item.id} className={`story ${item.kind.toLowerCase()}`} disabled={!item.symbol}
                        onClick={() => onSelect(item.symbol)}>
                    <span className="when">
                        {new Date(item.timestamp).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}
                        {" · "}{item.symbol ?? item.sector}
                    </span>
                    <span className="headline">{item.headline}</span>
                </button>
            ))}
        </div>
    );
}
