import { useCallback, useEffect, useState } from "react";

/**
 * Keys for your own trading programs. A program sends its key in an X-API-Key header instead of logging in.
 * The server only keeps a fingerprint (hash) of each key, so a new key can be shown exactly once: here, now.
 */
export default function ApiKeys() {
    const [keys, setKeys] = useState(null);
    const [label, setLabel] = useState("");
    const [created, setCreated] = useState(null);   // the key just made, until the user leaves this tab
    const [error, setError] = useState(null);

    const load = useCallback(async () => {
        try {
            const res = await fetch("/api/keys");
            if (res.ok) setKeys(await res.json());
        } catch {
            // the server is down: the list stays as it was
        }
    }, []);
    useEffect(() => { load(); }, [load]);

    async function create(e) {
        e.preventDefault();
        setError(null);
        const res = await fetch("/api/keys", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ label }),
        });
        const json = await res.json();
        if (!res.ok) {
            setError(json.detail ?? "Could not create a key");
            return;
        }
        setCreated(json);
        setLabel("");
        load();
    }

    async function remove(key) {
        await fetch(`/api/keys/${key.id}`, { method: "DELETE" });
        setCreated(null);
        load();
    }

    return (
        <div className="keys">
            <p className="none">
                An API key lets a program you write trade on your account. See <code>examples/bot.py</code> in the repository.
            </p>
            {created && (
                <div className="result ok newkey">
                    Copy the key for “{created.label}” now. It will not be shown again.
                    <code>{created.key}</code>
                </div>
            )}
            {keys && keys.length > 0 && (
                <table>
                    <thead><tr><th>Name</th><th>Key</th><th>Created</th><th /></tr></thead>
                    <tbody>
                        {keys.map((key) => (
                            <tr key={key.id}>
                                <td>{key.label}</td>
                                <td><code>{key.keyStart}…</code></td>
                                <td>{new Date(key.createdAt).toLocaleDateString()}</td>
                                <td><button className="cancel" onClick={() => remove(key)}>Delete</button></td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            )}
            <form className="newkey-form" onSubmit={create}>
                <input type="text" value={label} maxLength={40} placeholder="Name, e.g. momentum bot"
                       onChange={(e) => setLabel(e.target.value)} />
                <button type="submit" className="cancel">Create key</button>
            </form>
            {error && <div className="result err">{error}</div>}
        </div>
    );
}
