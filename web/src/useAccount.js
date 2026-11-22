import { useEffect, useState } from "react";

/**
 * Polls GET /api/account once a second while someone is logged in: cash, positions, open orders and recent fills.
 * The server works out whose account from the login cookie. Returns null until the first answer arrives,
 * while the server is down, and when nobody is logged in.
 */
export function useAccount(user, onLoggedOut) {
    const [account, setAccount] = useState(null);

    useEffect(() => {
        setAccount(null);   // never show the last user's numbers to the next one
        if (!user) return;

        let stopped = false;
        async function load() {
            try {
                const res = await fetch("/api/account");
                if (stopped) return;
                if (res.status === 401) onLoggedOut();
                setAccount(res.ok ? await res.json() : null);
            } catch {
                if (!stopped) setAccount(null);
            }
        }
        load();
        const timer = setInterval(load, 1_000);
        return () => {
            stopped = true;
            clearInterval(timer);
        };
    }, [user, onLoggedOut]);

    return account;
}
