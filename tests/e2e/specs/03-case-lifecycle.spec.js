/**
 * Suite 03 — Case Lifecycle
 *
 * Tests navigation and basic case page access.
 * Full create→assign→review flow requires specific test data.
 */
import { test, expect } from '../fixtures/auth.fixture.js'

test.describe('Case Lifecycle', () => {

  test('Admin can see cases page', async ({ adminPage }) => {
    await adminPage.goto('/cases')
    await adminPage.waitForTimeout(3000)
    expect(adminPage.url()).toContain('localhost')
    await expect(adminPage.locator('h1').or(adminPage.locator('table'))).toBeVisible({ timeout: 10_000 })
  })

  test('New Case button is visible for admin', async ({ adminPage }) => {
    await adminPage.goto('/cases')
    await adminPage.waitForTimeout(3000)
    await expect(adminPage.getByRole('button', { name: /new case|new application/i })).toBeVisible({ timeout: 10_000 })
  })

  test('New Case modal opens', async ({ adminPage }) => {
    await adminPage.goto('/cases')
    await adminPage.waitForTimeout(3000)

    await adminPage.getByRole('button', { name: /new case|new application/i }).click()
    await adminPage.waitForTimeout(1000)

    // Modal should be visible with form fields
    await expect(adminPage.getByText(/customer|product/i).first()).toBeVisible({ timeout: 5000 })
  })

  test('Reviewer can see cases page', async ({ reviewerPage }) => {
    await reviewerPage.goto('/cases')
    await reviewerPage.waitForTimeout(3000)
    expect(reviewerPage.url()).toContain('localhost')
    // Reviewer should see the cases list (may be empty)
    await expect(reviewerPage.locator('h1').or(reviewerPage.locator('table'))).toBeVisible({ timeout: 10_000 })
  })
})
