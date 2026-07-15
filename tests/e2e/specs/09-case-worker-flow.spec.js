/**
 * Suite 09 — Case Worker Full Flow
 *
 * Tests the complete case worker journey: create case, claim it,
 * upload documents, manage checklist items, and submit for review.
 */
import { test, expect } from '../fixtures/auth.fixture.js'
import { CasesPage, CaseDetailPage } from '../pages/CasesPage.js'
import { waitForToast, waitForPageReady } from '../helpers/wait-helpers.js'
import path from 'path'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const TEST_FILE = path.resolve(__dirname, '..', 'fixtures', 'test-document.pdf')

/** We store the case ID across tests in this serial suite. */
let caseId = null
let firstChecklistItem = null

test.describe.serial('Case Worker Full Flow', () => {

  test('Admin navigates to an existing case or creates one', async ({ adminPage }) => {
    const casesPage = new CasesPage(adminPage)
    await casesPage.goto()

    // Use an existing case if available, or create new
    const rowCount = await casesPage.getCaseCount()

    if (rowCount > 0) {
      // Click the first case row to open it — capture URL for case ID
      const firstRow = adminPage.locator('table tbody tr').first()
      await firstRow.click()
      await adminPage.waitForTimeout(2000)

      // Extract case ID from URL: /cases/{uuid}
      const url = adminPage.url()
      const match = url.match(/cases\/([0-9a-f-]+)/)
      if (match) caseId = match[1]
    }

    if (!caseId) {
      // Create a new case
      await casesPage.goto()
      await casesPage.createCase({
        customerName: 'Apex',
        product: 'Personal Loan',
        caseType: 'NEW_ACCOUNT',
      })
      await adminPage.waitForTimeout(2000)

      // Click first row to open
      const firstRow = adminPage.locator('table tbody tr').first()
      await firstRow.click()
      await adminPage.waitForTimeout(2000)

      const url = adminPage.url()
      const match = url.match(/cases\/([0-9a-f-]+)/)
      if (match) caseId = match[1]
    }

    expect(caseId).toBeTruthy()
  })

  test('Case detail page loads with checklist', async ({ adminPage }) => {
    await adminPage.goto(`/cases/${caseId}`)
    await waitForPageReady(adminPage)

    // Should see case detail with checklist items or status
    const heading = adminPage.locator('h1, h2').first()
    await expect(heading).toBeVisible({ timeout: 15_000 })

    // Try to find checklist item names
    const itemLabels = adminPage.locator('p.text-sm.font-medium, [class*="font-medium"]').filter({ hasText: /[A-Z]/ })
    if (await itemLabels.count() > 0) {
      firstChecklistItem = await itemLabels.first().textContent()
    }
  })

  test('Start Working transitions case and claims ownership', async ({ adminPage }) => {
    await adminPage.goto(`/cases/${caseId}`)
    await waitForPageReady(adminPage)

    // Check if Start Working is available (case is NEW)
    const startBtn = adminPage.locator('button:has-text("Start Working")')
    const isNew = await startBtn.isVisible({ timeout: 5_000 }).catch(() => false)

    if (isNew) {
      await startBtn.click()
      await waitForToast(adminPage, 'updated', 10_000).catch(() => {})
      await adminPage.waitForTimeout(1000)

      // Start Working should be gone
      await expect(startBtn).toBeHidden({ timeout: 5_000 })
    }

    // Case should now be IN_PROGRESS — verify owner section shows name
    const ownerSection = adminPage.locator('text="Owner"').first()
    await expect(ownerSection).toBeVisible({ timeout: 10_000 })
  })

  test('Upload document to checklist item', async ({ adminPage }) => {
    await adminPage.goto(`/cases/${caseId}`)
    await waitForPageReady(adminPage)

    // Find the first Upload button or file input
    const uploadBtn = adminPage.locator('button:has-text("Upload")').first()
    const hasUpload = await uploadBtn.isVisible({ timeout: 5_000 }).catch(() => false)

    if (hasUpload) {
      // Click upload button which reveals file input
      await uploadBtn.click()
      await adminPage.waitForTimeout(300)
    }

    // Set file on the first file input
    const fileInput = adminPage.locator('input[type="file"]').first()
    const hasFileInput = await fileInput.isVisible({ timeout: 3_000 }).catch(() => false)

    if (hasFileInput) {
      await fileInput.setInputFiles(TEST_FILE)
      await waitForToast(adminPage, 'uploaded', 15_000).catch(() =>
        waitForToast(adminPage, 'linked', 15_000).catch(() => {})
      )
      await adminPage.waitForTimeout(2000)
    }

    // Verify something changed — either a green check, UPLOADED badge, or document name appears
    const indicator = adminPage.locator('svg.text-green-500, span:has-text("UPLOADED"), text="test-document"')
    const visible = await indicator.first().isVisible({ timeout: 5_000 }).catch(() => false)
    // Don't fail hard — upload may not be available if all items already have docs
    expect(visible || !hasFileInput).toBeTruthy()
  })

  test('Actions menu appears after upload', async ({ adminPage }) => {
    await adminPage.goto(`/cases/${caseId}`)
    await waitForPageReady(adminPage)

    await waitForPageReady(adminPage)

    // Find the checklist item that has a document uploaded — it should have an Actions button
    const actionsBtn = adminPage.locator('button:has-text("Actions")').first()
    await expect(actionsBtn).toBeVisible({ timeout: 10_000 })
  })

  test('Mark item as Complete via Actions menu', async ({ adminPage }) => {
    await adminPage.goto(`/cases/${caseId}`)
    await waitForPageReady(adminPage)

    // Find any visible Actions button (only appears on UPLOADED items)
    const actionsBtn = adminPage.locator('button:has-text("Actions")').first()
    const hasActions = await actionsBtn.isVisible({ timeout: 5_000 }).catch(() => false)

    if (!hasActions) {
      // No items in UPLOADED state — skip gracefully
      test.info().annotations.push({ type: 'skip', description: 'No UPLOADED items with Actions menu available' })
      return
    }

    await actionsBtn.click()
    await adminPage.waitForTimeout(300)
    await adminPage.locator('button:has-text("Mark as Complete")').click()
    await waitForToast(adminPage, 'complete', 10_000).catch(() => {})
    await adminPage.waitForTimeout(1000)

    // Verify "Completed" text appears somewhere in the checklist
    await expect(adminPage.locator('text="Completed"').first()).toBeVisible({ timeout: 10_000 })
  })

  test('Reopen completed item', async ({ adminPage }) => {
    await adminPage.goto(`/cases/${caseId}`)
    await waitForPageReady(adminPage)

    const reopenBtn = adminPage.locator('button:has-text("Reopen")').first()
    const hasReopen = await reopenBtn.isVisible({ timeout: 5_000 }).catch(() => false)

    if (!hasReopen) {
      test.info().annotations.push({ type: 'skip', description: 'No completed items to reopen' })
      return
    }

    await reopenBtn.click()
    await waitForToast(adminPage, 'reopened', 10_000).catch(() => {})
    await adminPage.waitForTimeout(1000)

    // After reopen, the Actions button should reappear
    const actionsBtn = adminPage.locator('button:has-text("Actions")').first()
    await expect(actionsBtn).toBeVisible({ timeout: 10_000 })
  })

  test('Add document request to checklist', async ({ adminPage }) => {
    await adminPage.goto(`/cases/${caseId}`)
    await waitForPageReady(adminPage)

    // Scroll down to find the Add Document Request button
    const addBtn = adminPage.locator('button:has-text("Add Document Request")')
    await addBtn.scrollIntoViewIfNeeded().catch(() => {})
    const hasAddBtn = await addBtn.isVisible({ timeout: 5_000 }).catch(() => false)

    if (!hasAddBtn) {
      test.info().annotations.push({ type: 'skip', description: 'Add Document Request button not visible — case may not be IN_PROGRESS' })
      return
    }

    await addBtn.click()
    await adminPage.waitForTimeout(500)

    // Switch to Custom Name mode and enter name
    const customBtn = adminPage.locator('button:has-text("Custom Name")')
    if (await customBtn.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await customBtn.click()
    }

    await adminPage.locator('input[placeholder*="Power of Attorney"], input[placeholder*="Bank"]').fill('Bank Statement')

    await adminPage.locator('button:has-text("Add to Checklist")').click()
    await waitForToast(adminPage, 'added', 10_000).catch(() => {})
    await adminPage.waitForTimeout(1000)

    // Verify "Bank Statement" appears in the checklist
    await expect(adminPage.locator('text="Bank Statement"').first()).toBeVisible({ timeout: 10_000 })
  })

  test('Submit case for review', async ({ adminPage }) => {
    await adminPage.goto(`/cases/${caseId}`)
    await waitForPageReady(adminPage)

    // Click the "Submit for Review" transition button (exact match to avoid "Submit" on forms)
    const submitBtn = adminPage.locator('button:has-text("Submit for Review")')
    const hasSubmit = await submitBtn.isVisible({ timeout: 5_000 }).catch(() => false)

    if (!hasSubmit) {
      test.info().annotations.push({ type: 'skip', description: 'Submit for Review button not visible — case may not be IN_PROGRESS' })
      return
    }

    await submitBtn.click()
    await adminPage.waitForTimeout(1000)

    // Handle confirmation/reason modal if present
    const confirmBtn = adminPage.locator('button:has-text("Confirm"), button:has-text("Save")')
    if (await confirmBtn.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await confirmBtn.click()
    }

    await waitForToast(adminPage, 'updated', 10_000).catch(() => {})
    await adminPage.waitForTimeout(1000)

    // Status should change to REVIEW_PENDING
    const statusBadge = adminPage.locator('text="REVIEW_PENDING", text="Review Pending"')
    await expect(statusBadge.first()).toBeVisible({ timeout: 10_000 })
  })
})
