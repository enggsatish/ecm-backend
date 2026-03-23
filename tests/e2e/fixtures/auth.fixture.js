/**
 * Auth fixture — provides pre-authenticated browser contexts per role.
 *
 * Usage in tests:
 *   import { test } from '../fixtures/auth.fixture.js'
 *   test('my test', async ({ adminPage, reviewerPage }) => { ... })
 *
 * On first run, setup-auth.js generates cached session files in auth/.
 * Subsequent runs reuse those files — no Okta login redirect.
 */
import { test as base } from '@playwright/test'
import path from 'path'
import fs from 'fs'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const AUTH_DIR = path.resolve(__dirname, '..', 'auth')

export const ROLES = {
  admin:      { file: 'admin.json',      envUser: 'ADMIN_USERNAME',      envPass: 'ADMIN_PASSWORD' },
  superadmin: { file: 'superadmin.json', envUser: 'SUPERADMIN_USERNAME', envPass: 'SUPERADMIN_PASSWORD' },
  reviewer:   { file: 'reviewer.json',   envUser: 'REVIEWER_USERNAME',   envPass: 'REVIEWER_PASSWORD' },
  backoffice: { file: 'backoffice.json', envUser: 'BACKOFFICE_USERNAME', envPass: 'BACKOFFICE_PASSWORD' },
}

/**
 * Creates an authenticated browser context from a cached auth file.
 * Restores both cookies/localStorage (via Playwright storageState) AND
 * sessionStorage (injected via addInitScript — Playwright doesn't natively handle this).
 */
async function createAuthContext(browser, authFile) {
  const raw = JSON.parse(fs.readFileSync(authFile, 'utf-8'))
  const sessionStorageData = raw.sessionStorage || {}

  // Remove sessionStorage from the state before passing to Playwright
  // (Playwright doesn't understand this custom key)
  const { sessionStorage: _ss, ...playwrightState } = raw

  const ctx = await browser.newContext({ storageState: playwrightState })

  // Inject sessionStorage before any page loads
  if (Object.keys(sessionStorageData).length > 0) {
    await ctx.addInitScript((data) => {
      for (const [key, value] of Object.entries(data)) {
        try { sessionStorage.setItem(key, value) } catch {}
      }
    }, sessionStorageData)
  }

  return ctx
}

/**
 * Extended test fixture that provides role-specific authenticated pages.
 */
export const test = base.extend({
  adminPage: async ({ browser }, use) => {
    const ctx = await createAuthContext(browser, path.join(AUTH_DIR, ROLES.admin.file))
    const page = await ctx.newPage()
    await use(page)
    await ctx.close()
  },

  reviewerPage: async ({ browser }, use) => {
    const ctx = await createAuthContext(browser, path.join(AUTH_DIR, ROLES.reviewer.file))
    const page = await ctx.newPage()
    await use(page)
    await ctx.close()
  },

  backofficePage: async ({ browser }, use) => {
    const ctx = await createAuthContext(browser, path.join(AUTH_DIR, ROLES.backoffice.file))
    const page = await ctx.newPage()
    await use(page)
    await ctx.close()
  },

  superadminPage: async ({ browser }, use) => {
    const ctx = await createAuthContext(browser, path.join(AUTH_DIR, ROLES.superadmin.file))
    const page = await ctx.newPage()
    await use(page)
    await ctx.close()
  },
})

export { expect } from '@playwright/test'
