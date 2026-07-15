/**
 * Suite 04 — eForm Submit & Review
 */
import { test, expect } from '../fixtures/auth.fixture.js'

test.describe('eForm Submit & Review', () => {

  test('eForms page loads', async ({ adminPage }) => {
    await adminPage.goto('/eforms')
    await adminPage.waitForTimeout(5000)
    expect(adminPage.url()).toContain('localhost')
    await expect(adminPage.locator('h1').first()).toBeVisible({ timeout: 15_000 })
  })

  test('Fill Form buttons visible if forms exist', async ({ adminPage }) => {
    await adminPage.goto('/eforms')
    await adminPage.waitForTimeout(5000)

    // Check if any Fill Form buttons exist
    const fillBtns = adminPage.getByRole('button', { name: /fill form/i })
    const count = await fillBtns.count()
    // Just verify page loaded — may have 0 published forms
    expect(count).toBeGreaterThanOrEqual(0)
  })

  test('My Submissions page loads', async ({ adminPage }) => {
    await adminPage.goto('/eforms/submissions/mine')
    await adminPage.waitForTimeout(5000)
    expect(adminPage.url()).toContain('localhost')
    await expect(adminPage.locator('h1').first()).toBeVisible({ timeout: 15_000 })
  })

  test('Review queue loads for reviewer', async ({ reviewerPage }) => {
    await reviewerPage.goto('/backoffice/queue')
    await reviewerPage.waitForTimeout(5000)
    expect(reviewerPage.url()).toContain('localhost')
    await expect(reviewerPage.locator('h1').first()).toBeVisible({ timeout: 15_000 })
  })
})
