import { defineConfig } from '@playwright/test'
import dotenv from 'dotenv'
import path from 'path'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
dotenv.config({ path: path.resolve(__dirname, '.env.test') })

export default defineConfig({
  testDir: './specs',
  testMatch: '**/*.spec.js',

  // Run tests sequentially — many tests depend on state from previous steps
  fullyParallel: false,
  workers: 1,

  // Retry failed tests once
  retries: 1,

  // Timeout per test (2 minutes — some flows involve OCR/workflow polling)
  timeout: 120_000,

  // Expect timeout (10 seconds for assertions)
  expect: { timeout: 10_000 },

  // Reporter
  reporter: [
    ['html', { open: 'never' }],
    ['list'],
  ],

  use: {
    // Base URL — ECM frontend
    baseURL: process.env.ECM_BASE_URL || 'http://localhost:3000',

    // Browser settings
    headless: process.env.HEADED !== 'true',
    viewport: { width: 1440, height: 900 },
    ignoreHTTPSErrors: true,

    // Slow down every action by this many ms (0 = full speed)
    // Set SLOW_MO=500 in .env.test for a watchable pace
    launchOptions: {
      slowMo: parseInt(process.env.SLOW_MO || '0', 10),
    },

    // Artifacts on failure
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    trace: 'retain-on-failure',

    // Navigation timeout
    navigationTimeout: 30_000,
    actionTimeout: 15_000,
  },

  projects: [
    // Auth setup — runs first, generates cached sessions
    {
      name: 'auth-setup',
      testMatch: /setup-auth\.js/,
      testDir: './fixtures',
    },
    // Main test suites — use cached auth
    {
      name: 'chromium',
      use: { browserName: 'chromium' },
      dependencies: ['auth-setup'],
    },
  ],
})
