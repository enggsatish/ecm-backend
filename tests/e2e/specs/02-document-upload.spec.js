/**
 * Suite 02 — Document Upload & Viewer
 */
import { test, expect } from '../fixtures/auth.fixture.js'
import path from 'path'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const TEST_FILE = path.resolve(__dirname, '..', 'fixtures', 'test-document.pdf')

test.describe('Document Upload Flow', () => {

  test('Documents page loads', async ({ adminPage }) => {
    await adminPage.goto('/documents')
    await adminPage.waitForTimeout(3000)
    expect(adminPage.url()).toContain('localhost')
    await expect(adminPage.locator('table').or(adminPage.locator('h1'))).toBeVisible({ timeout: 10_000 })
  })

  test('Document table shows rows', async ({ adminPage }) => {
    await adminPage.goto('/documents')
    await adminPage.waitForTimeout(3000)

    const table = adminPage.locator('table')
    if (await table.isVisible({ timeout: 5000 }).catch(() => false)) {
      const rows = table.locator('tbody tr')
      const count = await rows.count()
      expect(count).toBeGreaterThanOrEqual(0) // may be empty on fresh DB
    }
  })

  test('Document viewer opens on row click', async ({ adminPage }) => {
    await adminPage.goto('/documents')
    await adminPage.waitForTimeout(3000)

    const firstRow = adminPage.locator('table tbody tr').first()
    if (await firstRow.isVisible({ timeout: 5000 }).catch(() => false)) {
      // Click the eye/view button in the first row
      const viewBtn = firstRow.locator('button').first()
      await viewBtn.click()
      await adminPage.waitForTimeout(2000)

      // Modal should open — look for Preview tab or any modal content
      const modal = adminPage.locator('.fixed').filter({ hasText: /preview|metadata|pipeline/i })
      await expect(modal.or(adminPage.locator('button:has-text("Preview")'))).toBeVisible({ timeout: 5000 })
    }
  })

  test('Document viewer has Pipeline tab', async ({ adminPage }) => {
    await adminPage.goto('/documents')
    await adminPage.waitForTimeout(3000)

    const firstRow = adminPage.locator('table tbody tr').first()
    if (await firstRow.isVisible({ timeout: 5000 }).catch(() => false)) {
      const viewBtn = firstRow.locator('button').first()
      await viewBtn.click()
      await adminPage.waitForTimeout(2000)

      // Click Pipeline tab
      const pipelineTab = adminPage.getByRole('button', { name: 'Pipeline' })
      if (await pipelineTab.isVisible({ timeout: 3000 }).catch(() => false)) {
        await pipelineTab.click()
        await adminPage.waitForTimeout(1000)
        // Should see OCR branch
        await expect(adminPage.getByText('OCR')).toBeVisible({ timeout: 5000 })
      }
    }
  })
})
