import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./perspective.js";   // starts Perspective (WebAssembly) before the first render
import App from "./App.jsx";
import "./styles.css";

createRoot(document.getElementById("root")).render(
    <StrictMode>
        <App />
    </StrictMode>,
);
