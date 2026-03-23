/**
 * Suite 07 — Role-Based Access Control
 *
 * Strategy: navigate to a page, wait for load, check that the page
 * rendered (not stuck on Okta login or showing access denied).
 * Uses loose assertions — checks for ANY content on the page, not specific headings.
 */
import { test, expect } from '../fixtures/auth.fixture.js'

test.describe('Role-Based Access', () => {

  // ── Admin Access ──────────────────────────────────────────────────

  test('Admin can access user management', async ({ adminPage }) => {
    await adminPage.goto('/admin/users')
    await adminPage.waitForTimeout(3000)
    expect(adminPage.url()).toContain('localhost')
    // Page should have a table or some admin content
    await expect(adminPage.locator('table').or(adminPage.locator('h1'))).toBeVisible({ timeout: 10_000 })
  })

  test('Admin can access products page', async ({ adminPage }) => {
    await adminPage.goto('/admin/products')
    await adminPage.waitForTimeout(3000)
    expect(adminPage.url()).toContain('localhost')
    await expect(adminPage.locator('table').or(adminPage.locator('h1'))).toBeVisible({ timeout: 10_000 })
  })

  test('Admin can access workflow designer', async ({ adminPage }) => {
    await adminPage.goto('/workflow/designer')
    await adminPage.waitForTimeout(3000)
    expect(adminPage.url()).toContain('localhost')
    await expect(adminPage.locator('h1').or(adminPage.locator('table'))).toBeVisible({ timeout: 10_000 })
  })

  test('Admin can access form designer', async ({ adminPage }) => {
    await adminPage.goto('/eforms/designer/list')
    await adminPage.waitForTimeout(3000)
    expect(adminPage.url()).toContain('localhost')
    await expect(adminPage.locator('h1').or(adminPage.locator('table'))).toBeVisible({ timeout: 10_000 })
  })

  // ── Reviewer Restrictions ─────────────────────────────────────────

  test('Reviewer CANNOT access admin user management', async ({ reviewerPage }) => {
    await reviewerPage.goto('/admin/users')
    await reviewerPage.waitForTimeout(3000)

    // Should be blocked — either access denied text or redirected away
    const url = reviewerPage.url()
    const isOnAdminUsers = url.includes('/admin/users')
    const hasAccessDenied = await reviewerPage.getByText(/not authorized|forbidden|access denied/i)
      .isVisible({ timeout: 3000 }).catch(() => false)

    expect(hasAccessDenied || !isOnAdminUsers).toBeTruthy()
  })

  test('Reviewer CAN access review queue', async ({ reviewerPage }) => {
    await reviewerPage.goto('/backoffice/queue')
    await reviewerPage.waitForTimeout(3000)
    expect(reviewerPage.url()).toContain('localhost')
    await expect(reviewerPage.locator('h1').or(reviewerPage.locator('table'))).toBeVisible({ timeout: 10_000 })
  })

  test('Reviewer CAN access eForms', async ({ reviewerPage }) => {
    await reviewerPage.goto('/eforms')
    await reviewerPage.waitForTimeout(3000)
    expect(reviewerPage.url()).toContain('localhost')
    await expect(reviewerPage.locator('h1')).toBeVisible({ timeout: 10_000 })
  })

  // ── Backoffice Restrictions ───────────────────────────────────────

  test('Backoffice CANNOT access admin pages', async ({ backofficePage }) => {
    await backofficePage.goto('/admin/users')
    await backofficePage.waitForTimeout(3000)

    const url = backofficePage.url()
    const isOnAdminUsers = url.includes('/admin/users')
    const hasAccessDenied = await backofficePage.getByText(/not authorized|forbidden|access denied/i)
      .isVisible({ timeout: 3000 }).catch(() => false)

    expect(hasAccessDenied || !isOnAdminUsers).toBeTruthy()
  })

  test('Backoffice CAN access review queue', async ({ backofficePage }) => {
    await backofficePage.goto('/backoffice/queue')
    await backofficePage.waitForTimeout(3000)
    expect(backofficePage.url()).toContain('localhost')
    await expect(backofficePage.locator('h1').or(backofficePage.locator('table'))).toBeVisible({ timeout: 10_000 })
  })

  test('Backoffice CAN access documents', async ({ backofficePage }) => {
    await backofficePage.goto('/documents')
    await backofficePage.waitForTimeout(3000)
    expect(backofficePage.url()).toContain('localhost')
    await expect(backofficePage.locator('h1').or(backofficePage.locator('table'))).toBeVisible({ timeout: 10_000 })
  })

  // ── All roles: Dashboard ──────────────────────────────────────────

  test('All roles can access their dashboard', async ({ adminPage, reviewerPage, backofficePage }) => {
    for (const page of [adminPage, reviewerPage, backofficePage]) {
      await page.goto('/dashboard')
      await page.waitForTimeout(3000)
      expect(page.url()).toContain('localhost')
      await expect(page.locator('aside')).toBeVisible({ timeout: 15_000 })
    }
  })
})
