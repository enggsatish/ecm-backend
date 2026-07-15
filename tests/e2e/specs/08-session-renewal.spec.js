/**
 * Suite 08 — Session Renewal & Expiry
 */
import { test, expect } from '../fixtures/auth.fixture.js'

test.describe('Session Management', () => {

  test('Authenticated page stays on localhost', async ({ adminPage }) => {
    await adminPage.goto('/dashboard')
    await adminPage.waitForTimeout(5000)
    expect(adminPage.url()).toContain('localhost')
    await expect(adminPage.locator('aside')).toBeVisible({ timeout: 15_000 })
  })

  test('API requests include Authorization header', async ({ adminPage }) => {
    let authHeaderSeen = false
    await adminPage.route('**/api/**', (route) => {
      const headers = route.request().headers()
      if (headers['authorization']?.startsWith('Bearer ')) {
        authHeaderSeen = true
      }
      route.continue()
    })

    await adminPage.goto('/dashboard')
    await adminPage.waitForTimeout(8000) // Wait for API calls to fire

    expect(authHeaderSeen).toBeTruthy()
  })

  test('Session expired event shows modal', async ({ adminPage }) => {
    await adminPage.goto('/dashboard')
    await adminPage.waitForTimeout(5000)
    // Ensure page is loaded
    await expect(adminPage.locator('aside')).toBeVisible({ timeout: 15_000 })

    // Fire the custom session expired event
    await adminPage.evaluate(() => {
      window.dispatchEvent(new CustomEvent('ecm:session-expired'))
    })
    await adminPage.waitForTimeout(1000)

    await expect(adminPage.getByText('Session Expired')).toBeVisible({ timeout: 5000 })
    await expect(adminPage.getByRole('button', { name: /sign in again/i })).toBeVisible()
  })

  test('Access token exists in session storage', async ({ adminPage }) => {
    await adminPage.goto('/dashboard')
    await adminPage.waitForTimeout(5000)

    const hasToken = await adminPage.evaluate(() => {
      const storage = sessionStorage.getItem('okta-token-storage')
      if (!storage) return false
      try {
        const parsed = JSON.parse(storage)
        return !!(parsed.accessToken?.accessToken)
      } catch {
        return false
      }
    })

    expect(hasToken).toBeTruthy()
  })
})
