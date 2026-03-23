/**
 * Suite 08 — Session Renewal & Expiry
 */
import { test, expect } from '../fixtures/auth.fixture.js'

test.describe('Session Management', () => {

  test('Authenticated page loads without redirect to Okta', async ({ adminPage }) => {
    await adminPage.goto('/dashboard')
    await adminPage.waitForLoadState('networkidle')
    // Wait a moment for any redirects to settle
    await adminPage.waitForTimeout(3000)

    const url = adminPage.url()
    // Should be on dashboard, not Okta
    expect(url).toContain('localhost')
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
    await adminPage.waitForLoadState('networkidle')
    // Wait for API calls to fire
    await adminPage.waitForTimeout(5000)

    expect(authHeaderSeen).toBeTruthy()
  })

  test('Session expired event triggers modal', async ({ adminPage }) => {
    await adminPage.goto('/dashboard')
    await adminPage.waitForLoadState('networkidle')
    await adminPage.waitForTimeout(2000)

    // Simulate session expired by dispatching the custom event
    await adminPage.evaluate(() => {
      window.dispatchEvent(new CustomEvent('ecm:session-expired'))
    })

    // Modal should appear
    await expect(adminPage.getByText('Session Expired')).toBeVisible({ timeout: 5000 })
    await expect(adminPage.getByRole('button', { name: /sign in again/i })).toBeVisible()
  })

  test('Access token exists in session storage', async ({ adminPage }) => {
    await adminPage.goto('/dashboard')
    await adminPage.waitForLoadState('networkidle')
    await adminPage.waitForTimeout(2000)

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
