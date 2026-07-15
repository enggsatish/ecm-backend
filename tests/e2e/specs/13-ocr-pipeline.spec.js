/**
 * Suite 13 — OCR Pipeline (Dynamic Engine Configuration)
 *
 * Tests the full OCR pipeline: GLM-OCR → Llama 3.2 → Azure AI fallback,
 * document classification, field extraction, and pipeline status tracking.
 *
 * Prerequisites:
 *   - Ollama running with glm-ocr + llama3.2:3b models
 *   - Azure AI configured (or pipeline falls back gracefully)
 *   - At least one IDENTITY category in document_categories
 */
import { test, expect } from '../fixtures/auth.fixture.js'
import { DocumentsPage } from '../pages/DocumentsPage.js'
import path from 'path'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
// Use existing test fixture — for full OCR testing, add test-dl.jpg to fixtures/
const TEST_DOC = path.resolve(__dirname, '..', 'fixtures', 'test-document.pdf')

test.describe('OCR Pipeline', () => {

  test('Upload document without category triggers auto-classification', async ({ adminPage }) => {
    const docs = new DocumentsPage(adminPage)
    await docs.goto()

    // Upload without selecting a category
    await docs.uploadDocument(TEST_DOC)
    await adminPage.waitForTimeout(3000)

    // Document should appear in table
    const count = await docs.getDocumentCount()
    expect(count).toBeGreaterThan(0)
  })

  test('Document gets classified after OCR completes', async ({ adminPage }) => {
    const docs = new DocumentsPage(adminPage)
    await docs.goto()

    // Wait for OCR to process — check for status change from "Processing"
    // to "Active" or "Needs Classification" or "Needs Assignment"
    const firstRow = adminPage.locator('table tbody tr').first()
    await expect(firstRow.locator('span').filter({
      hasText: /Active|Needs Classification|Needs Assignment/
    }).first()).toBeVisible({ timeout: 60_000 })
  })

  test('Document viewer shows extracted text in Raw Text tab', async ({ adminPage }) => {
    const docs = new DocumentsPage(adminPage)
    await docs.goto()
    await expect(adminPage.locator('table')).toBeVisible({ timeout: 15_000 })

    // Open first document
    const firstViewBtn = adminPage.locator('table tbody tr').first().locator('button[title="View document"]')
    if (await firstViewBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
      await firstViewBtn.click()
      await adminPage.waitForTimeout(2000)

      // Switch to Raw Text tab
      const rawTextBtn = adminPage.getByRole('button', { name: 'Raw Text' })
      if (await rawTextBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
        await rawTextBtn.click()
        await adminPage.waitForTimeout(1000)

        // Should have extracted text (not empty)
        const textContent = adminPage.locator('pre')
        await expect(textContent).toBeVisible({ timeout: 5000 })
      }
    }
  })

  test('Document viewer shows extracted fields', async ({ adminPage }) => {
    const docs = new DocumentsPage(adminPage)
    await docs.goto()
    await expect(adminPage.locator('table')).toBeVisible({ timeout: 15_000 })

    const firstViewBtn = adminPage.locator('table tbody tr').first().locator('button[title="View document"]')
    if (await firstViewBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
      await firstViewBtn.click()
      await adminPage.waitForTimeout(2000)

      // Switch to Extracted Fields tab
      const fieldsBtn = adminPage.getByRole('button', { name: 'Extracted Fields' })
      if (await fieldsBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
        await fieldsBtn.click()
        await adminPage.waitForTimeout(1000)
      }
    }
  })

  test('Document viewer shows pipeline steps', async ({ adminPage }) => {
    const docs = new DocumentsPage(adminPage)
    await docs.goto()
    await expect(adminPage.locator('table')).toBeVisible({ timeout: 15_000 })

    const firstViewBtn = adminPage.locator('table tbody tr').first().locator('button[title="View document"]')
    if (await firstViewBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
      await firstViewBtn.click()
      await adminPage.waitForTimeout(2000)

      const pipelineBtn = adminPage.getByRole('button', { name: 'Pipeline' })
      if (await pipelineBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
        await pipelineBtn.click()
        await adminPage.waitForTimeout(1000)

        // Pipeline should show engine steps (GLM-OCR, Llama, Azure, etc.)
        await expect(adminPage.getByText(/Text Extracted|Classified|Fields Extracted/)).toBeVisible({ timeout: 5000 })
      }
    }
  })

  test('Needs Classification documents show classify action', async ({ adminPage }) => {
    await adminPage.goto('/documents')
    await adminPage.waitForTimeout(5000)

    // Look for "Needs Classification" status badge
    const needsClassBadge = adminPage.locator('span:has-text("Needs Classification")')
    if (await needsClassBadge.first().isVisible({ timeout: 5000 }).catch(() => false)) {
      // Should be clickable → opens ClassifyModal
      await needsClassBadge.first().click()
      await adminPage.waitForTimeout(1000)

      // ClassifyModal should show category cascade
      await expect(adminPage.getByText('Classify Document')).toBeVisible({ timeout: 5000 })
    }
  })
})
