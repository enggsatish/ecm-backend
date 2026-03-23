/**
 * Suite 05 — Workflow Designer
 */
import { test, expect } from '../fixtures/auth.fixture.js'

test.describe('Workflow Designer', () => {

  test('Admin can see workflow designer page', async ({ adminPage }) => {
    await adminPage.goto('/workflow/designer')
    await adminPage.waitForTimeout(3000)
    expect(adminPage.url()).toContain('localhost')
    await expect(adminPage.locator('h1')).toBeVisible({ timeout: 10_000 })
  })

  test('Templates tab shows content', async ({ adminPage }) => {
    await adminPage.goto('/workflow/designer')
    await adminPage.waitForTimeout(3000)
    // Should see Templates tab (active by default)
    await expect(adminPage.getByRole('button', { name: 'Templates' })).toBeVisible({ timeout: 10_000 })
  })

  test('New Template button is visible', async ({ adminPage }) => {
    await adminPage.goto('/workflow/designer')
    await adminPage.waitForTimeout(3000)
    await expect(adminPage.getByRole('button', { name: /new template/i })).toBeVisible({ timeout: 10_000 })
  })

  test('Category Mappings tab is accessible', async ({ adminPage }) => {
    await adminPage.goto('/workflow/designer')
    await adminPage.waitForTimeout(3000)

    const mappingsTab = adminPage.getByRole('button', { name: /category mapping/i })
    await expect(mappingsTab).toBeVisible({ timeout: 10_000 })
    await mappingsTab.click()
    await adminPage.waitForTimeout(1000)

    // Should see the mapping form (select dropdowns)
    await expect(adminPage.locator('select').first()).toBeVisible({ timeout: 5000 })
  })
})
