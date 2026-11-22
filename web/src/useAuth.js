import { useCallback, useEffect, useState } from "react";

/**
 * Who is logged in. `user` is undefined until the server has answered, null when nobody is, otherwise the username.
 *
 * The session itself is a cookie that the browser keeps and sends by itself with every request to /api.
 * JavaScript cannot read that cookie (it is HttpOnly), so the username the server tells us is all this code knows.
 */
export function useAuth() {
    const [user, setUser] = useState(undefined);
    const [signupOpen, setSignupOpen] = useState(true);

    useEffect(() => {
        let stopped = false;
        let timer;
        async function ask() {
            try {
                const me = await (await fetch("/api/auth/me")).json();
                if (stopped) return;
                setUser(me.username ?? null);
                setSignupOpen(me.signupOpen);
            } catch {
                if (!stopped) timer = setTimeout(ask, 2_000);   // the server is down: keep asking until it is back
            }
        }
        ask();
        return () => {
            stopped = true;
            clearTimeout(timer);
        };
    }, []);

    /** action is "login" or "register". Returns null if it worked, or the server's reason if it did not. */
    const submit = useCallback(async (action, username, password) => {
        try {
            const res = await fetch(`/api/auth/${action}`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ username, password }),
            });
            const json = await res.json();
            if (!res.ok) return json.detail ?? "That did not work";
            setUser(json.username);
            return null;
        } catch {
            return "Could not reach the server";
        }
    }, []);

    const logout = useCallback(async () => {
        try {
            await fetch("/api/auth/logout", { method: "POST" });
        } finally {
            setUser(null);
        }
    }, []);

    /** For when the server answers 401: the session has expired or was logged out somewhere else. */
    const loggedOut = useCallback(() => setUser(null), []);

    return { user, signupOpen, submit, logout, loggedOut };
}
