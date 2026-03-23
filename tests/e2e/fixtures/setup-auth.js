/**
 * Auth Setup — Playwright project dependency.
 *
 * Logs in to Okta for each test role and saves the browser session
 * (cookies + sessionStorage) to auth/*.json files.
 *
 * Runs ONCE before all test suites (configured as a Playwright project dependency).
 * If auth files already exist and are fresh (<30 min), setup is skipped.
 *
 * Run manually: npm run setup
 * Or: npx playwright test --project auth-setup
 */
import { test as setup } from '@playwright/test'
import { ROLES } from './auth.fixture.js'
import { OKTA } from '../helpers/selectors.js'
import path from 'path'
import fs from 'fs'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const AUTH_DIR = path.resolve(__dirname, '..', 'auth')
const MAX_AGE_MS = 30 * 60_000  // 30 minutes — refresh auth if older

/**
 * Automates the Okta login flow.
 * Handles both the classic Okta sign-in widget and the OIE (Okta Identity Engine) flow.
 */
async function loginViaOkta(page, username, password, baseURL) {
  // Navigate to the app — this triggers the PKCE redirect to Okta
  await page.goto(baseURL + '/dashboard')

  // Wait for Okta login page to appear
  const oktaDomain = process.env.OKTA_LOGIN_DOMAIN || 'okta.com'
  await page.waitForURL(url => url.hostname.includes(oktaDomain), { timeout: 30_000 })

  // Fill username
  await page.locator(OKTA.usernameInput).waitFor({ timeout: 10_000 })
  await page.locator(OKTA.usernameInput).fill(username)

  // Some Okta configs have a "Next" button before password
  const nextBtn = page.locator(OKTA.nextButton)
  if (await nextBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
    await nextBtn.click()
    // Wait for password field to appear
    await page.locator(OKTA.passwordInput).waitFor({ timeout: 10_000 })
  }

  // Fill password
  await page.locator(OKTA.passwordInput).fill(password)

  // Submit
  await page.locator(OKTA.submitButton).click()

  // Wait for redirect back to the app (dashboard or callback)
  await page.waitForURL(url => !url.hostname.includes(oktaDomain), { timeout: 30_000 })

  // Wait for the dashboard to fully load (auth complete)
  await page.waitForURL('**/dashboard', { timeout: 30_000 })
  await page.waitForLoadState('networkidle')

  // Small delay to ensure sessionStorage is populated with tokens
  await page.waitForTimeout(2000)
}

function isAuthFresh(filePath) {
  try {
    const stats = fs.statSync(filePath)
    return Date.now() - stats.mtimeMs < MAX_AGE_MS
  } catch {
    return false
  }
}

// Generate auth state for each role
for (const [roleName, roleConfig] of Object.entries(ROLES)) {
  setup(`authenticate as ${roleName}`, async ({ page, baseURL }) => {
    const authFile = path.join(AUTH_DIR, roleConfig.file)

    // Skip if auth file is fresh
    if (isAuthFresh(authFile)) {
      setup.skip(true, `Auth file for ${roleName} is still fresh`)
      return
    }

    const username = process.env[roleConfig.envUser]
    const password = process.env[roleConfig.envPass]

    if (!username || !password) {
      setup.skip(true, `Credentials not set for ${roleName} (${roleConfig.envUser}/${roleConfig.envPass})`)
      return
    }

    console.log(`🔐 Logging in as ${roleName}: ${username}`)
    await loginViaOkta(page, username, password, baseURL)

    // Save authenticated state (cookies + localStorage via Playwright)
    fs.mkdirSync(AUTH_DIR, { recursive: true })
    const storageState = await page.context().storageState()

    // Also capture sessionStorage (Okta stores tokens here — Playwright doesn't save it natively)
    const sessionStorageData = await page.evaluate(() => {
      const data = {}
      for (let i = 0; i < sessionStorage.length; i++) {
        const key = sessionStorage.key(i)
        data[key] = sessionStorage.getItem(key)
      }
      return data
    })

    // Merge sessionStorage into the state file
    storageState.sessionStorage = sessionStorageData
    fs.writeFileSync(authFile, JSON.stringify(storageState, null, 2))
    console.log(`✅ Auth saved: ${authFile} (${Object.keys(sessionStorageData).length} sessionStorage keys)`)
  })
}
