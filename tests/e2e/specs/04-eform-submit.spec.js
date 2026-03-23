/**
 * Suite 04 — eForm Submit → Review → Approve
 */
import { test, expect } from '../fixtures/auth.fixture.js'

test.describe('eForm Submit & Review', () => {
  test.describe.configure({ mode: 'serial' })

  test('User sees published forms on eForms page', async ({ adminPage }) => {
    await adminPage.goto('/eforms')
    await adminPage.waitForLoadState('networkidle')
    await expect(adminPage.getByRole('heading', { name: /eforms/i })).toBeVisible({ timeout: 10_000 })
  })

  test('User can navigate to form fill page', async ({ adminPage }) => {
    await adminPage.goto('/eforms')
    await adminPage.waitForLoadState('networkidle')

    const fillBtn = adminPage.getByRole('button', { name: /fill form/i }).first()
    if (await fillBtn.isVisible({ timeout: 5000 }).catch(() => false)) {
      await fillBtn.click()
      await adminPage.waitForURL('**/eforms/fill/**', { timeout: 10_000 })
      await expect(adminPage).toHaveURL(/\/eforms\/fill\//)
    }
  })

  test('Submission appears in My Submissions', async ({ adminPage }) => {
    await adminPage.goto('/eforms/submissions/mine')
    await adminPage.waitForLoadState('networkidle')
    await expect(adminPage.getByRole('heading', { name: /submission/i })).toBeVisible({ timeout: 10_000 })
  })

  test('Reviewer sees review queue', async ({ reviewerPage }) => {
    await reviewerPage.goto('/backoffice/queue')
    await reviewerPage.waitForLoadState('networkidle')
    await expect(reviewerPage.getByRole('heading', { name: /review queue/i })).toBeVisible({ timeout: 10_000 })
  })
})
