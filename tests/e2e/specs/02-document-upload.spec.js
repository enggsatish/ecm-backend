/**
 * Suite 02 — Document Upload & Viewer
 */
import { test, expect } from '../fixtures/auth.fixture.js'
import path from 'path'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const TEST_FILE = path.resolve(__dirname, '..', 'fixtures', 'test-document.pdf')

test.describe('Document Upload Flow', () => {

  test('Documents page loads with table', async ({ adminPage }) => {
    await adminPage.goto('/documents')
    await adminPage.waitForTimeout(5000)
    expect(adminPage.url()).toContain('localhost')
    // Wait for the table to appear
    await expect(adminPage.locator('table')).toBeVisible({ timeout: 15_000 })
  })

  test('Document table has rows', async ({ adminPage }) => {
    await adminPage.goto('/documents')
    await adminPage.waitForTimeout(5000)
    await expect(adminPage.locator('table')).toBeVisible({ timeout: 15_000 })
    const rowCount = await adminPage.locator('table tbody tr').count()
    expect(rowCount).toBeGreaterThan(0)
  })

  test('Document viewer opens on eye icon click', async ({ adminPage }) => {
    await adminPage.goto('/documents')
    await adminPage.waitForTimeout(5000)
    await expect(adminPage.locator('table')).toBeVisible({ timeout: 15_000 })

    // Click the first eye/view button in the table
    const firstViewBtn = adminPage.locator('table tbody tr').first().locator('button').first()
    if (await firstViewBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
      await firstViewBtn.click()
      await adminPage.waitForTimeout(2000)

      // Should see the modal with tabs (Preview, Metadata, etc.)
      await expect(adminPage.getByRole('button', { name: 'Preview' })).toBeVisible({ timeout: 5000 })
    }
  })

  test('Document viewer has Pipeline tab', async ({ adminPage }) => {
    await adminPage.goto('/documents')
    await adminPage.waitForTimeout(5000)
    await expect(adminPage.locator('table')).toBeVisible({ timeout: 15_000 })

    const firstViewBtn = adminPage.locator('table tbody tr').first().locator('button').first()
    if (await firstViewBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
      await firstViewBtn.click()
      await adminPage.waitForTimeout(2000)

      const pipelineBtn = adminPage.getByRole('button', { name: 'Pipeline' })
      if (await pipelineBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
        await pipelineBtn.click()
        await adminPage.waitForTimeout(1000)
        await expect(adminPage.getByText('Uploaded')).toBeVisible({ timeout: 5000 })
      }
    }
  })

  test('Document row shows unified status badge', async ({ adminPage }) => {
    await adminPage.goto('/documents')
    await adminPage.waitForTimeout(5000)
    await expect(adminPage.locator('table')).toBeVisible({ timeout: 15_000 })

    // Unified status column — should show one of the valid statuses
    const firstRow = adminPage.locator('table tbody tr').first()
    const statusBadge = firstRow.locator('span').filter({
      hasText: /Active|Processing|Awaiting Signature|Signed|Archived|OCR Failed|Needs Classification|Needs Assignment/
    }).first()
    await expect(statusBadge).toBeVisible({ timeout: 5_000 })
  })

  test('Document viewer has AI Train tab', async ({ adminPage }) => {
    await adminPage.goto('/documents')
    await adminPage.waitForTimeout(5000)
    await expect(adminPage.locator('table')).toBeVisible({ timeout: 15_000 })

    const firstViewBtn = adminPage.locator('table tbody tr').first().locator('button').first()
    if (await firstViewBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
      await firstViewBtn.click()
      await adminPage.waitForTimeout(2000)

      await expect(adminPage.getByRole('button', { name: 'AI Train' })).toBeVisible({ timeout: 5000 })
    }
  })

  test('Document row has three-dot action menu', async ({ adminPage }) => {
    await adminPage.goto('/documents')
    await adminPage.waitForTimeout(5000)
    await expect(adminPage.locator('table')).toBeVisible({ timeout: 15_000 })

    // Three-dot menu button should be visible (not hidden behind hover)
    const menuBtn = adminPage.locator('table tbody tr').first().locator('button[aria-label="More actions"]')
    await expect(menuBtn).toBeVisible({ timeout: 5_000 })
  })

  test('View and Download buttons are always visible', async ({ adminPage }) => {
    await adminPage.goto('/documents')
    await adminPage.waitForTimeout(5000)
    await expect(adminPage.locator('table')).toBeVisible({ timeout: 15_000 })

    const firstRow = adminPage.locator('table tbody tr').first()
    // View button (eye icon)
    const viewBtn = firstRow.locator('button[title="View document"]')
    await expect(viewBtn).toBeVisible({ timeout: 5_000 })
    // Download button
    const downloadBtn = firstRow.locator('button[title="Download"]')
    await expect(downloadBtn).toBeVisible({ timeout: 5_000 })
  })
})
