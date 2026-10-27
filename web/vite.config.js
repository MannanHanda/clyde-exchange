import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// In dev, the browser talks only to Vite (:5173), and Vite forwards API and
// WebSocket traffic to the Spring Boot server (:8080). No CORS setup needed.
export default defineConfig({
    plugins: [react()],
    build: { target: "esnext" },   // Perspective uses top-level await and WebAssembly
    server: {
        proxy: {
            "/api": "http://localhost:8080",
            "/ws": { target: "ws://localhost:8080", ws: true },
        },
    },
});
