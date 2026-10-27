// Perspective runs its data engine in a Web Worker (WebAssembly). We create two
// in-browser tables once, here; the WebSocket hook writes into them and every
// <perspective-viewer> on the page is a live view over one of them.
import perspective from "@finos/perspective";
import perspective_viewer from "@finos/perspective-viewer";
import "@finos/perspective-viewer-datagrid";
import "@finos/perspective-viewer-d3fc";
import "@finos/perspective-viewer/dist/css/pro-dark.css";

import SERVER_WASM from "@finos/perspective/dist/wasm/perspective-server.wasm?url";
import CLIENT_WASM from "@finos/perspective-viewer/dist/wasm/perspective-viewer.wasm?url";

await Promise.all([
    perspective.init_server(fetch(SERVER_WASM)),
    perspective_viewer.init_client(fetch(CLIENT_WASM)),
]);

const worker = await perspective.worker();

/** Every trade we've seen. Capped: once full, the oldest rows are overwritten. */
export const tradesTable = await worker.table(
    {
        symbol: "string",
        time: "datetime",
        side: "string",       // the aggressor: whoever crossed the spread
        price: "float",       // pounds, for display (the server sends integer ticks)
        qty: "integer",
    },
    { limit: 20_000 },
);

/** One row per (symbol, depth level). Indexed, so each update overwrites rows in place. */
export const bookTable = await worker.table(
    {
        key: "string",
        symbol: "string",
        level: "integer",
        bid_qty: "integer",
        bid: "float",
        ask: "float",
        ask_qty: "integer",
    },
    { index: "key" },
);
