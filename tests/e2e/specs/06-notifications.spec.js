/**
 * Suite 06 — Notifications
 */
import { test, expect } from '../fixtures/auth.fixture.js'

test.describe('Notifications', () => {

  test('Admin sees header', async ({ adminPage }) => {
    await adminPage.goto('/dashboard')
    await adminPage.waitForTimeout(3000)
    await expect(adminPage.locator('header')).toBeVisible({ timeout: 10_000 })
  })

  test('Reviewer sees header', async ({ reviewerPage }) => {
    await reviewerPage.goto('/dashboard')
    await reviewerPage.waitForTimeout(3000)
    await expect(reviewerPage.locator('header')).toBeVisible({ timeout: 10_000 })
  })

  test('Admin can access notification preferences', async ({ adminPage }) => {
    await adminPage.goto('/admin/notifications')
    await adminPage.waitForTimeout(3000)
    expect(adminPage.url()).toContain('localhost')
    // Page should load with some content
    await expect(adminPage.locator('h1').or(adminPage.locator('table')).or(adminPage.locator('label'))).toBeVisible({ timeout: 10_000 })
  })
})
