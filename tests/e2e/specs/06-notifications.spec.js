/**
 * Suite 06 — Notifications
 */
import { test, expect } from '../fixtures/auth.fixture.js'

test.describe('Notifications', () => {

  test('Admin sees header on dashboard', async ({ adminPage }) => {
    await adminPage.goto('/dashboard')
    await adminPage.waitForTimeout(5000)
    await expect(adminPage.locator('header')).toBeVisible({ timeout: 15_000 })
  })

  test('Reviewer sees header on dashboard', async ({ reviewerPage }) => {
    await reviewerPage.goto('/dashboard')
    await reviewerPage.waitForTimeout(5000)
    await expect(reviewerPage.locator('header')).toBeVisible({ timeout: 15_000 })
  })

  test('Notification preferences page loads', async ({ adminPage }) => {
    await adminPage.goto('/admin/notifications')
    await adminPage.waitForTimeout(5000)
    expect(adminPage.url()).toContain('localhost')
    // Should have some content loaded (labels, toggles, etc.)
    await expect(adminPage.locator('main').first()).toBeVisible({ timeout: 15_000 })
  })
})
