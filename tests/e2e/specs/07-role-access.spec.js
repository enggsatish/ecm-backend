/**
 * Suite 07 — Role-Based Access Control
 *
 * Strategy: Navigate to a page, verify page loaded on localhost (not Okta redirect),
 * then check for content. Uses simple locators that match the actual DOM.
 */
import { test, expect } from '../fixtures/auth.fixture.js'

test.describe('Role-Based Access', () => {

  // ── Admin Access ──────────────────────────────────────────────────

  test('Admin can access user management', async ({ adminPage }) => {
    await adminPage.goto('/admin/users')
    await adminPage.waitForTimeout(5000)
    expect(adminPage.url()).toContain('localhost')
    // The page shows a heading "Users" in the header
    await expect(adminPage.locator('h1').first()).toBeVisible({ timeout: 15_000 })
  })

  test('Admin can access products page', async ({ adminPage }) => {
    await adminPage.goto('/admin/products')
    await adminPage.waitForTimeout(5000)
    expect(adminPage.url()).toContain('localhost')
    await expect(adminPage.locator('h1').first()).toBeVisible({ timeout: 15_000 })
  })

  test('Admin can access workflow designer', async ({ adminPage }) => {
    await adminPage.goto('/workflow/designer')
    await adminPage.waitForTimeout(5000)
    expect(adminPage.url()).toContain('localhost')
    await expect(adminPage.locator('h1').first()).toBeVisible({ timeout: 15_000 })
  })

  test('Admin can access form designer', async ({ adminPage }) => {
    await adminPage.goto('/eforms/designer/list')
    await adminPage.waitForTimeout(5000)
    expect(adminPage.url()).toContain('localhost')
    await expect(adminPage.locator('h1').first()).toBeVisible({ timeout: 15_000 })
  })

  // ── Reviewer Restrictions ─────────────────────────────────────────

  test('Reviewer CANNOT access admin user management', async ({ reviewerPage }) => {
    await reviewerPage.goto('/admin/users')
    await reviewerPage.waitForTimeout(5000)

    const url = reviewerPage.url()
    const hasAccessDenied = await reviewerPage.getByText(/not authorized|forbidden|access denied/i)
      .first().isVisible({ timeout: 3000 }).catch(() => false)
    const notOnAdminPage = !url.includes('/admin/users')

    expect(hasAccessDenied || notOnAdminPage).toBeTruthy()
  })

  test('Reviewer CAN access review queue', async ({ reviewerPage }) => {
    await reviewerPage.goto('/backoffice/queue')
    await reviewerPage.waitForTimeout(5000)
    expect(reviewerPage.url()).toContain('localhost')
    await expect(reviewerPage.locator('h1').first()).toBeVisible({ timeout: 15_000 })
  })

  test('Reviewer CAN access eForms', async ({ reviewerPage }) => {
    await reviewerPage.goto('/eforms')
    await reviewerPage.waitForTimeout(5000)
    expect(reviewerPage.url()).toContain('localhost')
    await expect(reviewerPage.locator('h1').first()).toBeVisible({ timeout: 15_000 })
  })

  // ── Backoffice Restrictions ───────────────────────────────────────

  test('Backoffice CANNOT access admin pages', async ({ backofficePage }) => {
    await backofficePage.goto('/admin/users')
    await backofficePage.waitForTimeout(5000)

    const url = backofficePage.url()
    const hasAccessDenied = await backofficePage.getByText(/not authorized|forbidden|access denied/i)
      .first().isVisible({ timeout: 3000 }).catch(() => false)
    const notOnAdminPage = !url.includes('/admin/users')

    expect(hasAccessDenied || notOnAdminPage).toBeTruthy()
  })

  test('Backoffice CAN access review queue', async ({ backofficePage }) => {
    await backofficePage.goto('/backoffice/queue')
    await backofficePage.waitForTimeout(5000)
    expect(backofficePage.url()).toContain('localhost')
    await expect(backofficePage.locator('h1').first()).toBeVisible({ timeout: 15_000 })
  })

  test('Backoffice CAN access documents', async ({ backofficePage }) => {
    await backofficePage.goto('/documents')
    await backofficePage.waitForTimeout(5000)
    expect(backofficePage.url()).toContain('localhost')
    await expect(backofficePage.locator('h1').first()).toBeVisible({ timeout: 15_000 })
  })

  // ── Integration access (SUPER_ADMIN only) ────────────────────────

  test('Super admin CAN access integrations', async ({ superadminPage }) => {
    await superadminPage.goto('/admin/integrations')
    await superadminPage.waitForTimeout(5000)
    // Should see the integrations page (DocuSign tab etc.)
    await expect(superadminPage.locator('h1, h2').first()).toBeVisible({ timeout: 15_000 })
    expect(superadminPage.url()).toContain('/admin/integrations')
  })

  test('Admin CANNOT access integrations', async ({ adminPage }) => {
    await adminPage.goto('/admin/integrations')
    await adminPage.waitForTimeout(5000)
    // Should be redirected or see access denied
    const url = adminPage.url()
    const hasAccessDenied = await adminPage.getByText(/not authorized|forbidden|access denied/i)
      .first().isVisible({ timeout: 3000 }).catch(() => false)
    const notOnPage = !url.includes('/admin/integrations')
    expect(hasAccessDenied || notOnPage).toBeTruthy()
  })

  test('Reviewer CANNOT access integrations', async ({ reviewerPage }) => {
    await reviewerPage.goto('/admin/integrations')
    await reviewerPage.waitForTimeout(5000)
    const url = reviewerPage.url()
    const notOnPage = !url.includes('/admin/integrations')
    expect(notOnPage).toBeTruthy()
  })

  // ── Branding endpoint access ────────────────────────────────────

  test('All roles can load branding config', async ({ adminPage, reviewerPage, backofficePage }) => {
    // The /api/admin/config/branding endpoint should be accessible by any authenticated user
    for (const page of [adminPage, reviewerPage, backofficePage]) {
      const response = await page.request.get('/api/admin/config/branding')
      expect(response.status()).toBe(200)
    }
  })

  // ── Cross-role dashboard ──────────────────────────────────────────

  test('All roles can access dashboard', async ({ adminPage, reviewerPage, backofficePage }) => {
    for (const page of [adminPage, reviewerPage, backofficePage]) {
      await page.goto('/dashboard')
      await page.waitForTimeout(5000)
      expect(page.url()).toContain('localhost')
      await expect(page.locator('aside')).toBeVisible({ timeout: 15_000 })
    }
  })
})
