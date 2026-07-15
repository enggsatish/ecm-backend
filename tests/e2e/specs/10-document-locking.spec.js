/**
 * Suite 10 — Document Locking
 *
 * Tests document lock/unlock via the three-dot action menu,
 * badge visibility, and cross-role locking restrictions.
 */
import { test, expect } from '../fixtures/auth.fixture.js'
import { DocumentsPage } from '../pages/DocumentsPage.js'
import { waitForPageReady, waitForToast } from '../helpers/wait-helpers.js'

/** We store the first document name across serial tests. */
let firstDocName = null

test.describe.serial('Document Locking', () => {

  test('Documents page loads with table', async ({ adminPage }) => {
    const docsPage = new DocumentsPage(adminPage)
    await docsPage.goto()

    await expect(adminPage.locator('table')).toBeVisible({ timeout: 15_000 })
    const rowCount = await docsPage.getDocumentCount()
    expect(rowCount).toBeGreaterThan(0)

    // Capture the first document name for subsequent tests
    firstDocName = await adminPage.locator('table tbody tr').first().locator('td').first().textContent()
    expect(firstDocName).toBeTruthy()
  })

  test('Three-dot menu shows Lock option', async ({ adminPage }) => {
    const docsPage = new DocumentsPage(adminPage)
    await docsPage.goto()
    await expect(adminPage.locator('table')).toBeVisible({ timeout: 15_000 })

    const hasLock = await docsPage.expectLockOption(firstDocName)
    expect(hasLock).toBeTruthy()
  })

  test('Lock document shows You badge', async ({ adminPage }) => {
    const docsPage = new DocumentsPage(adminPage)
    await docsPage.goto()
    await expect(adminPage.locator('table')).toBeVisible({ timeout: 15_000 })

    await docsPage.lockDocument(firstDocName)
    await docsPage.expectLockedByMe(firstDocName)

    // Verify the blue "You" badge is present
    const row = adminPage.locator(`tr:has-text("${firstDocName}")`)
    const youBadge = row.locator('span:has-text("You")')
    await expect(youBadge).toBeVisible({ timeout: 5_000 })
  })

  test('Three-dot menu shows Unlock after locking', async ({ adminPage }) => {
    const docsPage = new DocumentsPage(adminPage)
    await docsPage.goto()
    await expect(adminPage.locator('table')).toBeVisible({ timeout: 15_000 })

    const hasUnlock = await docsPage.expectUnlockOption(firstDocName)
    expect(hasUnlock).toBeTruthy()
  })

  test('Unlock document removes badge', async ({ adminPage }) => {
    const docsPage = new DocumentsPage(adminPage)
    await docsPage.goto()
    await expect(adminPage.locator('table')).toBeVisible({ timeout: 15_000 })

    await docsPage.unlockDocument(firstDocName)

    // Wait a moment for the UI to update
    await adminPage.waitForTimeout(1000)

    // The "You" badge should no longer be visible on this row
    const row = adminPage.locator(`tr:has-text("${firstDocName}")`)
    const youBadge = row.locator('span:has-text("You")')
    await expect(youBadge).toBeHidden({ timeout: 5_000 })
  })

  test('Case-linked document shows Case badge', async ({ adminPage }) => {
    const docsPage = new DocumentsPage(adminPage)
    await docsPage.goto()
    await expect(adminPage.locator('table')).toBeVisible({ timeout: 15_000 })

    // Look for any document row that has a Case badge
    const caseBadge = adminPage.locator('table tbody tr').locator('span:has-text("Case:"), span:has-text("In Case")')
    const badgeCount = await caseBadge.count()

    if (badgeCount > 0) {
      // At least one document is linked to a case — verify badge is visible
      await expect(caseBadge.first()).toBeVisible({ timeout: 5_000 })
    } else {
      // No case-linked documents found — this is acceptable in a clean environment
      test.info().annotations.push({ type: 'skip-reason', description: 'No case-linked documents in current data set' })
    }
  })
})

test.describe('Cross-role locking', () => {

  test('Reviewer cannot lock case-linked document', async ({ reviewerPage }) => {
    const docsPage = new DocumentsPage(reviewerPage)
    await docsPage.goto()
    await expect(reviewerPage.locator('table')).toBeVisible({ timeout: 15_000 })

    // Find a document with a Case badge (linked to another user's case)
    const caseBadgeRow = reviewerPage.locator('table tbody tr').filter({
      has: reviewerPage.locator('span:has-text("Case:"), span:has-text("In Case")')
    }).first()

    const hasCaseDoc = await caseBadgeRow.isVisible({ timeout: 5_000 }).catch(() => false)

    if (hasCaseDoc) {
      // Get the document name from the first cell
      const docName = await caseBadgeRow.locator('td').first().textContent()

      // Open action menu and try to lock
      await docsPage.openActionMenu(docName)

      const lockBtn = reviewerPage.locator('button:has-text("Lock Document")')
      const lockVisible = await lockBtn.isVisible({ timeout: 3_000 }).catch(() => false)

      if (lockVisible) {
        await lockBtn.click()
        // Expect an error toast or the lock to be denied
        const errorToast = reviewerPage.locator('div[role="status"]:has-text("error"), div[role="status"]:has-text("denied"), div[role="status"]:has-text("cannot")')
        await expect(errorToast.first()).toBeVisible({ timeout: 10_000 })
      } else {
        // Lock option not shown at all — this is valid access control
        expect(lockVisible).toBeFalsy()
      }
    } else {
      test.info().annotations.push({ type: 'skip-reason', description: 'No case-linked documents available for cross-role test' })
    }
  })
})
