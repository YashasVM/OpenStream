import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./tests",
  reporter: "line",
  use: { baseURL: "http://127.0.0.1:1420", trace: "retain-on-failure" },
  webServer: { command: "npm run dev -- --host 127.0.0.1", url: "http://127.0.0.1:1420", reuseExistingServer: false },
  projects: [
    { name: "scale-100", use: { viewport: { width: 1180, height: 760 }, deviceScaleFactor: 1 } },
    { name: "scale-150", use: { viewport: { width: 1180, height: 760 }, deviceScaleFactor: 1.5 } },
    { name: "scale-200", use: { viewport: { width: 1180, height: 760 }, deviceScaleFactor: 2 } },
  ],
});
