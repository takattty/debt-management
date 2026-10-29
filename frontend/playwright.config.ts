import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://127.0.0.1:5173',
    // Network traces retain login bodies and session cookies; keep visual diagnostics only.
    trace: 'off',
    screenshot: 'only-on-failure',
    ...devices['Desktop Chrome'],
    launchOptions: process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH, args: ['--no-sandbox'] } : {},
  },
  webServer: process.env.E2E_BASE_URL ? undefined : {
    command: 'npm run dev',
    url: 'http://127.0.0.1:5173',
    reuseExistingServer: false,
  },
});
