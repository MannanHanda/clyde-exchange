import { useEffect, useRef } from "react";

/**
 * React wrapper around the <perspective-viewer> web component.
 * Loads the table once, then re-applies `config` whenever it changes
 * (e.g. a new symbol filter). Pass a memoised config to avoid needless restores.
 */
export default function Viewer({ table, config }) {
    const ref = useRef(null);
    const loading = useRef(null);

    useEffect(() => {
        const viewer = ref.current;
        loading.current ??= viewer.load(table);
        loading.current.then(() => viewer.restore({ theme: "Pro Dark", settings: false, ...config }));
    }, [table, config]);

    return <perspective-viewer ref={ref} />;
}
