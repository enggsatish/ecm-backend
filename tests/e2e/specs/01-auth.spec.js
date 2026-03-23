/**
 * Suite 01 — Authentication & Role-Based Navigation
 */
import { test, expect } from '../fixtures/auth.fixture.js'

test.describe('Authentication & Navigation', () => {

  test('Admin sees dashboard after login', async ({ adminPage }) => {
    await adminPage.goto('/dashboard')
    await adminPage.waitForTimeout(3000)
    // Should be on the app, not Okta
    expect(adminPage.url()).toContain('localhost')
    await expect(adminPage.locator('aside nav')).toBeVisible({ timeout: 15_000 })
  })

  test('Admin sidebar shows operational links', async ({ adminPage }) => {
    await adminPage.goto('/dashboard')
    await adminPage.waitForTimeout(3000)

    // Check sidebar nav links exist
    const nav = adminPage.locator('aside nav')
    await expect(nav.getByRole('link', { name: 'Dashboard' })).toBeVisible({ timeout: 10_000 })
    await expect(nav.getByRole('link', { name: 'Documents' })).toBeVisible()
    await expect(nav.getByRole('link', { name: 'Review Queue' })).toBeVisible()
    await expect(nav.getByRole('link', { name: 'Cases' })).toBeVisible()
  })

  test('Reviewer sees limited sidebar', async ({ reviewerPage }) => {
    await reviewerPage.goto('/dashboard')
    await reviewerPage.waitForTimeout(3000)

    const nav = reviewerPage.locator('aside nav')
    await expect(nav.getByRole('link', { name: 'Dashboard' })).toBeVisible({ timeout: 15_000 })
    await expect(nav.getByRole('link', { name: 'Review Queue' })).toBeVisible()
  })

  test('Backoffice user sees review queue and documents', async ({ backofficePage }) => {
    await backofficePage.goto('/dashboard')
    await backofficePage.waitForTimeout(3000)

    const nav = backofficePage.locator('aside nav')
    await expect(nav.getByRole('link', { name: 'Review Queue' })).toBeVisible({ timeout: 15_000 })
    await expect(nav.getByRole('link', { name: 'Documents' })).toBeVisible()
  })
})
