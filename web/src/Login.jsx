import { useState } from "react";

/** Shown where the order ticket goes while nobody is logged in. `auth` comes from useAuth(). */
export default function Login({ auth }) {
    const [mode, setMode] = useState("login");
    const [username, setUsername] = useState("");
    const [password, setPassword] = useState("");
    const [error, setError] = useState(null);
    const [busy, setBusy] = useState(false);
    const registering = mode === "register";

    async function submit(e) {
        e.preventDefault();
        setBusy(true);
        setError(await auth.submit(mode, username.trim().toLowerCase(), password));
        setBusy(false);   // on success App swaps this form for the order ticket
    }

    function choose(next) {
        setMode(next);
        setError(null);
    }

    return (
        <form className="ticket" onSubmit={submit}>
            <div className="ticket-title">Log in to trade</div>
            {auth.signupOpen && (
                <div className="toggle">
                    <button type="button" className={registering ? "" : "on"} onClick={() => choose("login")}>Log in</button>
                    <button type="button" className={registering ? "on" : ""} onClick={() => choose("register")}>Create account</button>
                </div>
            )}
            <div className="hint">
                {registering
                    ? "You get a paper-trading account with pretend money, and trade against the bots and everyone else."
                    : "You can watch the market without logging in. Trading needs an account."}
            </div>
            <label>
                Username
                <input type="text" value={username} autoComplete="username" autoCapitalize="none" spellCheck={false}
                       minLength={3} maxLength={20} placeholder={registering ? "3 to 20 letters, digits or _" : ""}
                       onChange={(e) => setUsername(e.target.value)} required />
            </label>
            <label>
                Password
                <input type="password" value={password} autoComplete={registering ? "new-password" : "current-password"}
                       minLength={registering ? 8 : undefined} placeholder={registering ? "at least 8 characters" : ""}
                       onChange={(e) => setPassword(e.target.value)} required />
            </label>
            <button type="submit" className="send go" disabled={busy}>
                {registering ? "Create account" : "Log in"}
            </button>
            {error && <div className="result err">{error}</div>}
        </form>
    );
}
