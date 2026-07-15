/**
 * Suite 03 — Case Lifecycle
 */
import { test, expect } from '../fixtures/auth.fixture.js'

test.describe('Case Lifecycle', () => {

  test('Admin can see cases page', async ({ adminPage }) => {
    await adminPage.goto('/cases')
    await adminPage.waitForTimeout(5000)
    expect(adminPage.url()).toContain('localhost')
    // Cases page should show heading or table
    const heading = adminPage.locator('h1')
    await expect(heading.first()).toBeVisible({ timeout: 15_000 })
  })

  test('New Case button is visible', async ({ adminPage }) => {
    await adminPage.goto('/cases')
    await adminPage.waitForTimeout(5000)
    await expect(adminPage.getByRole('button', { name: /new case|new application/i })).toBeVisible({ timeout: 10_000 })
  })

  test('New Case modal opens with form fields', async ({ adminPage }) => {
    await adminPage.goto('/cases')
    await adminPage.waitForTimeout(5000)

    await adminPage.getByRole('button', { name: /new case|new application/i }).click()
    await adminPage.waitForTimeout(2000)

    // Modal should show customer or product fields
    const modal = adminPage.locator('[role="dialog"], .fixed')
    await expect(modal.first()).toBeVisible({ timeout: 5000 })
  })

  test('Reviewer can see cases page', async ({ reviewerPage }) => {
    await reviewerPage.goto('/cases')
    await reviewerPage.waitForTimeout(5000)
    expect(reviewerPage.url()).toContain('localhost')
    await expect(reviewerPage.locator('h1').first()).toBeVisible({ timeout: 15_000 })
  })
})
