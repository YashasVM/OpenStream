import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  clearScreen: false,
  server: { port: 1420, strictPort: true },
  envPrefix: ["VITE_", "TAURI_ENV_"],
  build: { target: "es2022", minify: "esbuild", sourcemap: false },
  test: { environment: "jsdom", exclude: ["tests/**", "node_modules/**"] },
});
