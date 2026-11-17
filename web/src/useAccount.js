import { useEffect, useState } from "react";

// Whose account the dashboard trades with. There is no login yet, so it is fixed here
// (or set VITE_ACCOUNT when starting Vite). It must exist in the server's application.yml.
export const ACCOUNT = import.meta.env.VITE_ACCOUNT ?? "mannan";

/**
 * Polls GET /api/accounts/{id} once a second: cash, positions, open orders and recent fills.
 * Returns null until the first answer arrives (or while the server is down).
 */
export function useAccount() {
    const [account, setAccount] = useState(null);

    useEffect(() => {
        let stopped = false;
        async function load() {
            try {
                const res = await fetch(`/api/accounts/${ACCOUNT}`);
                if (!stopped) setAccount(res.ok ? await res.json() : null);
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
    }, []);

    return account;
}
