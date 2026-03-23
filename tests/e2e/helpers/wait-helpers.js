/**
 * Custom wait utilities for ECM-specific async patterns.
 */

/**
 * Wait for a toast notification with specific text.
 * react-hot-toast renders in a portal; text may appear briefly.
 */
export async function waitForToast(page, text, timeout = 10_000) {
  await page.locator(`div[role="status"]:has-text("${text}")`).waitFor({ timeout })
}

/**
 * Wait for navigation to a specific path.
 */
export async function waitForPath(page, pathPrefix, timeout = 15_000) {
  await page.waitForURL(`**${pathPrefix}*`, { timeout })
}

/**
 * Wait for the page to finish loading (no spinners visible).
 */
export async function waitForPageReady(page, timeout = 15_000) {
  // Wait for any Loader2 spinners to disappear
  await page.locator('.animate-spin').waitFor({ state: 'hidden', timeout }).catch(() => {})
  // Wait for network to settle
  await page.waitForLoadState('networkidle', { timeout }).catch(() => {})
}

/**
 * Wait for a table to have at least N rows.
 */
export async function waitForTableRows(page, minRows = 1, timeout = 15_000) {
  await page.locator(`table tbody tr:nth-child(${minRows})`).waitFor({ timeout })
}

/**
 * Poll a condition until it's true or timeout.
 * Useful for waiting on async backend processing (OCR, workflow start).
 */
export async function pollUntil(page, checkFn, { interval = 3000, timeout = 60_000 } = {}) {
  const start = Date.now()
  while (Date.now() - start < timeout) {
    const result = await checkFn()
    if (result) return result
    await page.waitForTimeout(interval)
  }
  throw new Error(`pollUntil timed out after ${timeout}ms`)
}

/**
 * Dismiss any visible toast notifications (clicks them away).
 */
export async function dismissToasts(page) {
  const toasts = page.locator('div[role="status"] button')
  const count = await toasts.count()
  for (let i = 0; i < count; i++) {
    await toasts.nth(i).click().catch(() => {})
  }
}
