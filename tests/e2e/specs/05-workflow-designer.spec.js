/**
 * Suite 05 — Workflow Designer
 */
import { test, expect } from '../fixtures/auth.fixture.js'

test.describe('Workflow Designer', () => {

  test('Workflow designer page loads', async ({ adminPage }) => {
    await adminPage.goto('/workflow/designer')
    await adminPage.waitForTimeout(5000)
    expect(adminPage.url()).toContain('localhost')
    await expect(adminPage.locator('h1').first()).toBeVisible({ timeout: 15_000 })
  })

  test('New Template button visible', async ({ adminPage }) => {
    await adminPage.goto('/workflow/designer')
    await adminPage.waitForTimeout(5000)
    await expect(adminPage.getByRole('button', { name: /new template/i })).toBeVisible({ timeout: 10_000 })
  })

  test('Templates tab and Category Mappings tab exist', async ({ adminPage }) => {
    await adminPage.goto('/workflow/designer')
    await adminPage.waitForTimeout(5000)

    await expect(adminPage.getByRole('button', { name: 'Templates' })).toBeVisible({ timeout: 10_000 })
    await expect(adminPage.getByRole('button', { name: /category mapping/i })).toBeVisible()
  })

  test('Category Mappings tab shows mapping form', async ({ adminPage }) => {
    await adminPage.goto('/workflow/designer')
    await adminPage.waitForTimeout(5000)

    await adminPage.getByRole('button', { name: /category mapping/i }).click()
    await adminPage.waitForTimeout(1000)

    // Should see select dropdowns for category and workflow
    await expect(adminPage.locator('select').first()).toBeVisible({ timeout: 5000 })
  })
})
