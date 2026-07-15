/**
 * Suite 11 — DocuSign Integration UI
 *
 * Tests the DocuSign UI elements: Send for Signature modal, placement options,
 * Pipeline tab eSign branch, and admin configuration pages.
 * NOTE: No actual DocuSign API calls are made — these are UI-only validations.
 */
import { test, expect } from '../fixtures/auth.fixture.js'
import { CasesPage, CaseDetailPage } from '../pages/CasesPage.js'
import { DocumentsPage } from '../pages/DocumentsPage.js'
import { waitForPageReady, waitForToast } from '../helpers/wait-helpers.js'
import path from 'path'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const TEST_FILE = path.resolve(__dirname, '..', 'fixtures', 'test-document.pdf')

/** Store state across the serial suite. */
let caseRef = null
let uploadedItemName = null

test.describe.serial('DocuSign Integration UI', () => {

  test('Case with uploaded document shows Send for Signature in Actions', async ({ adminPage }) => {
    const casesPage = new CasesPage(adminPage)
    await casesPage.goto()

    // Get the first case that exists (or create one if needed)
    const firstRow = adminPage.locator('table tbody tr').first()
    await expect(firstRow).toBeVisible({ timeout: 15_000 })
    caseRef = await firstRow.locator('td').first().textContent()

    await casesPage.openCase(caseRef)
    const detail = new CaseDetailPage(adminPage)

    // Make sure the case is in working state
    const startBtn = adminPage.locator('button:has-text("Start Working")')
    if (await startBtn.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await detail.startWorking()
    }

    // Find a checklist item with an uploaded document, or upload one
    const actionsBtn = adminPage.locator('button:has-text("Actions")').first()
    const hasActions = await actionsBtn.isVisible({ timeout: 5_000 }).catch(() => false)

    if (!hasActions) {
      // Upload to the first available checklist item
      const fileInput = adminPage.locator('input[type="file"]').first()
      if (await fileInput.isVisible({ timeout: 3_000 }).catch(() => false)) {
        await fileInput.setInputFiles(TEST_FILE)
        await waitForToast(adminPage, 'uploaded', 15_000).catch(() => {})
        await adminPage.waitForTimeout(2000)
      }
    }

    // Open the Actions menu and verify "Send for Signature" is present
    const actionsTrigger = adminPage.locator('button:has-text("Actions")').first()
    await expect(actionsTrigger).toBeVisible({ timeout: 10_000 })
    await actionsTrigger.click()
    await adminPage.waitForTimeout(300)

    const signatureOption = adminPage.locator('button:has-text("Send for Signature")')
    await expect(signatureOption).toBeVisible({ timeout: 5_000 })

    // Capture the item name for later tests
    uploadedItemName = await adminPage.locator('button:has-text("Actions")').first()
      .locator('..').locator('..').locator('span, p, label').first().textContent().catch(() => null)
  })

  test('Send for Signature modal opens with fields', async ({ adminPage }) => {
    const casesPage = new CasesPage(adminPage)
    await casesPage.goto()
    await casesPage.openCase(caseRef)

    await waitForPageReady(adminPage)

    const detail = new CaseDetailPage(adminPage)

    // Open the Actions menu and click Send for Signature
    const actionsBtn = adminPage.locator('button:has-text("Actions")').first()
    await actionsBtn.click()
    await adminPage.waitForTimeout(300)
    await adminPage.locator('button:has-text("Send for Signature")').click()
    await adminPage.waitForTimeout(500)

    // Verify the modal is open with required fields
    const modal = adminPage.locator('[role="dialog"], .fixed').filter({
      has: adminPage.locator('text="Send for Signature"')
    })
    await expect(modal.first()).toBeVisible({ timeout: 5_000 })

    // Email field
    const emailInput = adminPage.locator('input[type="email"]')
    await expect(emailInput).toBeVisible({ timeout: 3_000 })

    // Signer name field
    const nameInput = adminPage.locator('input[placeholder*="John Smith"], input[placeholder*="name"], label:has-text("Name") + input, label:has-text("Signer") ~ input')
    await expect(nameInput.first()).toBeVisible({ timeout: 3_000 })

    // Initials checkbox
    const initialsCheckbox = adminPage.locator('text="initials", text="Initials"').first()
    const hasInitials = await initialsCheckbox.isVisible().catch(() => false)
    expect(hasInitials).toBeTruthy()

    // Close modal
    await adminPage.keyboard.press('Escape')
  })

  test('Modal has three placement options', async ({ adminPage }) => {
    const casesPage = new CasesPage(adminPage)
    await casesPage.goto()
    await casesPage.openCase(caseRef)

    await waitForPageReady(adminPage)

    // Open Send for Signature modal
    const actionsBtn = adminPage.locator('button:has-text("Actions")').first()
    await actionsBtn.click()
    await adminPage.waitForTimeout(300)
    await adminPage.locator('button:has-text("Send for Signature")').click()
    await adminPage.waitForTimeout(500)

    // Verify three placement radio options
    const autoDetect = adminPage.locator('text="Auto-detect", text="Auto detect", label:has-text("Auto")')
    const lastPage = adminPage.locator('text="Last page", text="last page", label:has-text("Last page")')
    const specificPos = adminPage.locator('text="Specific position", text="Specific", label:has-text("Specific")')

    await expect(autoDetect.first()).toBeVisible({ timeout: 5_000 })
    await expect(lastPage.first()).toBeVisible({ timeout: 3_000 })
    await expect(specificPos.first()).toBeVisible({ timeout: 3_000 })

    await adminPage.keyboard.press('Escape')
  })

  test('Specific position shows coordinate fields', async ({ adminPage }) => {
    const casesPage = new CasesPage(adminPage)
    await casesPage.goto()
    await casesPage.openCase(caseRef)

    await waitForPageReady(adminPage)

    // Open Send for Signature modal
    const actionsBtn = adminPage.locator('button:has-text("Actions")').first()
    await actionsBtn.click()
    await adminPage.waitForTimeout(300)
    await adminPage.locator('button:has-text("Send for Signature")').click()
    await adminPage.waitForTimeout(500)

    // Select "Specific position" radio
    const specificRadio = adminPage.locator('input[value="specific"], input[value="specificPosition"]')
      .or(adminPage.locator('label:has-text("Specific")').locator('input[type="radio"]'))
    await specificRadio.first().check()
    await adminPage.waitForTimeout(300)

    // Verify Page, X, Y coordinate inputs appear
    const pageInput = adminPage.locator('input[placeholder*="Page"], label:has-text("Page") ~ input, input[name="page"]')
    const xInput = adminPage.locator('input[placeholder*="X"], label:has-text("X") ~ input, input[name="x"]')
    const yInput = adminPage.locator('input[placeholder*="Y"], label:has-text("Y") ~ input, input[name="y"]')

    await expect(pageInput.first()).toBeVisible({ timeout: 5_000 })
    await expect(xInput.first()).toBeVisible({ timeout: 3_000 })
    await expect(yInput.first()).toBeVisible({ timeout: 3_000 })

    await adminPage.keyboard.press('Escape')
  })

  test('Pipeline tab shows eSign branch when document is pending signature', async ({ adminPage }) => {
    const docsPage = new DocumentsPage(adminPage)
    await docsPage.goto()
    await expect(adminPage.locator('table')).toBeVisible({ timeout: 15_000 })

    // Look for a document with PENDING_SIGNATURE status
    const pendingSigRow = adminPage.locator('table tbody tr').filter({
      has: adminPage.locator('span:has-text("PENDING_SIGNATURE"), span:has-text("Pending Signature"), span:has-text("Awaiting Signature")')
    }).first()

    const hasPendingDoc = await pendingSigRow.isVisible({ timeout: 5_000 }).catch(() => false)

    if (hasPendingDoc) {
      // Click the view button to open the document viewer
      const docName = await pendingSigRow.locator('td').first().textContent()
      await docsPage.openDocumentViewer(docName)
      await docsPage.expectViewerOpen()

      // Switch to Pipeline tab
      await docsPage.selectViewerTab('Pipeline')
      await adminPage.waitForTimeout(1000)

      // Verify eSign branch is visible
      const eSignBranch = adminPage.locator('text="eSign", text="DocuSign", text="Signature"')
      await expect(eSignBranch.first()).toBeVisible({ timeout: 5_000 })

      await docsPage.closeViewer()
    } else {
      test.info().annotations.push({ type: 'skip-reason', description: 'No documents with PENDING_SIGNATURE status found' })
    }
  })
})

test.describe('DocuSign Admin Config', () => {

  test('Super admin can access DocuSign settings', async ({ superadminPage }) => {
    await superadminPage.goto('/admin/integrations')
    await waitForPageReady(superadminPage)

    // Should see the integrations page with a DocuSign tab or section
    const docusignTab = superadminPage.locator('button:has-text("DocuSign"), a:has-text("DocuSign"), text="DocuSign"')
    await expect(docusignTab.first()).toBeVisible({ timeout: 15_000 })
  })

  test('DocuSign settings has email branding section', async ({ superadminPage }) => {
    await superadminPage.goto('/admin/integrations')
    await waitForPageReady(superadminPage)

    // Click DocuSign tab if it is a tabbed layout
    const docusignTab = superadminPage.locator('button:has-text("DocuSign")')
    if (await docusignTab.isVisible({ timeout: 5_000 }).catch(() => false)) {
      await docusignTab.click()
      await superadminPage.waitForTimeout(500)
    }

    // Verify email branding fields are present
    const companyNameField = superadminPage.locator(
      'input[name="companyName"], label:has-text("Company Name") ~ input, input[placeholder*="Company"]'
    )
    const emailSubjectField = superadminPage.locator(
      'input[name="emailSubjectTemplate"], label:has-text("Email Subject") ~ input, input[placeholder*="Subject"]'
    )
    const emailBodyField = superadminPage.locator(
      'textarea[name="emailBodyTemplate"], label:has-text("Email Body") ~ textarea, textarea[placeholder*="Body"]'
    )

    await expect(companyNameField.first()).toBeVisible({ timeout: 10_000 })
    await expect(emailSubjectField.first()).toBeVisible({ timeout: 5_000 })
    await expect(emailBodyField.first()).toBeVisible({ timeout: 5_000 })
  })

  test('Reviewer cannot access integrations', async ({ reviewerPage }) => {
    await reviewerPage.goto('/admin/integrations')
    await reviewerPage.waitForTimeout(3000)

    // Reviewer should be redirected away or see access denied
    const currentUrl = reviewerPage.url()
    const accessDenied = reviewerPage.locator('text="Access Denied", text="Forbidden", text="Not Authorized", text="403"')
    const wasRedirected = !currentUrl.includes('/admin/integrations')
    const showsAccessDenied = await accessDenied.first().isVisible({ timeout: 5_000 }).catch(() => false)

    expect(wasRedirected || showsAccessDenied).toBeTruthy()
  })
})
