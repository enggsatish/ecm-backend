import { waitForPageReady, waitForToast, pollUntil } from '../helpers/wait-helpers.js'

export class DocumentsPage {
  constructor(page) { this.page = page }

  async goto() {
    await this.page.goto('/documents')
    await waitForPageReady(this.page)
  }

  async uploadDocument(filePath, categoryName) {
    const uploadBtn = this.page.locator('button:has-text("Upload")')
    if (await uploadBtn.isVisible()) await uploadBtn.click()

    if (categoryName) {
      const categorySelect = this.page.locator('select').filter({ hasText: 'Category' }).first()
        || this.page.locator('select').first()
      if (await categorySelect.isVisible().catch(() => false)) {
        await categorySelect.selectOption({ label: categoryName })
      }
    }

    const fileInput = this.page.locator('input[type="file"]')
    await fileInput.setInputFiles(filePath)
    await waitForToast(this.page, 'uploaded', 15_000).catch(() => {})
    await this.page.waitForTimeout(1000)
  }

  async getDocumentCount() {
    return this.page.locator('table tbody tr').count()
  }

  /** Open document viewer by clicking the eye icon */
  async openDocumentViewer(documentName) {
    const row = this.page.locator(`tr:has-text("${documentName}")`)
    await row.locator('button[title="View document"], button:has(svg)').first().click()
    await this.page.waitForTimeout(1000)
  }

  /** Open the three-dot action menu on a document row */
  async openActionMenu(documentName) {
    const row = this.page.locator(`tr:has-text("${documentName}")`)
    await row.locator('button[aria-label="More actions"]').click()
    await this.page.waitForTimeout(300)
  }

  /** Lock a document via three-dot menu */
  async lockDocument(documentName) {
    await this.openActionMenu(documentName)
    await this.page.locator('button:has-text("Lock Document")').click()
    await waitForToast(this.page, 'locked', 10_000).catch(() => {})
  }

  /** Unlock a document via three-dot menu */
  async unlockDocument(documentName) {
    await this.openActionMenu(documentName)
    await this.page.locator('button:has-text("Unlock Document")').click()
    await waitForToast(this.page, 'unlocked', 10_000).catch(() => {})
  }

  /** Archive a document via three-dot menu */
  async archiveDocument(documentName) {
    await this.openActionMenu(documentName)
    this.page.on('dialog', dialog => dialog.accept())
    await this.page.locator('button:has-text("Archive")').click()
    await waitForToast(this.page, 'archived', 10_000).catch(() => {})
  }

  /** Check lock badge shows "You" */
  async expectLockedByMe(documentName) {
    const row = this.page.locator(`tr:has-text("${documentName}")`)
    await row.locator('span:has-text("You")').waitFor({ timeout: 5_000 })
  }

  /** Check "Case:" badge is visible */
  async expectCaseBadge(documentName) {
    const row = this.page.locator(`tr:has-text("${documentName}")`)
    await row.locator('span:has-text("Case:")').waitFor({ timeout: 5_000 })
  }

  /** Check "In Case" badge is visible */
  async expectInCaseBadge(documentName) {
    const row = this.page.locator(`tr:has-text("${documentName}")`)
    await row.locator('span:has-text("In Case"), span:has-text("Case:")').waitFor({ timeout: 5_000 })
  }

  /** Get the unified status text for a document */
  async getDocumentStatus(documentName) {
    const row = this.page.locator(`tr:has-text("${documentName}")`)
    const badge = row.locator('td:nth-child(5) span').first()
    return badge.textContent()
  }

  /** Wait for document status to change */
  async expectDocumentStatus(documentName, status) {
    await this.page.locator(`tr:has-text("${documentName}") span:has-text("${status}")`).waitFor({ timeout: 10_000 })
  }

  /** Check three-dot menu shows "Unlock" (meaning we own the lock) */
  async expectUnlockOption(documentName) {
    await this.openActionMenu(documentName)
    const unlock = this.page.locator('button:has-text("Unlock Document")')
    const visible = await unlock.isVisible()
    await this.page.keyboard.press('Escape') // close menu
    return visible
  }

  /** Check three-dot menu shows "Lock" (meaning document is unlocked) */
  async expectLockOption(documentName) {
    await this.openActionMenu(documentName)
    const lock = this.page.locator('button:has-text("Lock Document")')
    const visible = await lock.isVisible()
    await this.page.keyboard.press('Escape')
    return visible
  }

  async waitForOcrComplete(documentName, timeout = 120_000) {
    return pollUntil(this.page, async () => {
      const row = this.page.locator(`tr:has-text("${documentName}")`)
      return row.locator('span:has-text("Active")').isVisible().catch(() => false)
    }, { interval: 5000, timeout })
  }

  async expectViewerOpen() {
    await this.page.locator('text="Preview"').waitFor({ timeout: 5000 })
  }

  async selectViewerTab(tabName) {
    await this.page.locator(`button:has-text("${tabName}")`).click()
  }

  async closeViewer() {
    await this.page.keyboard.press('Escape')
  }

  async search(query) {
    await this.page.locator('input[placeholder*="Search"]').fill(query)
    await this.page.waitForTimeout(500)
  }

  // ── AI Train helpers ──────────────────────────────────────────────

  /** Open AI Train tab in the document viewer */
  async openAiTrainTab() {
    await this.page.getByRole('button', { name: 'AI Train' }).click()
    await this.page.waitForTimeout(2000)
  }

  /** Draw a selection rectangle on the AI Train image */
  async drawRegion(xOffset = 50, yOffset = 50, width = 200, height = 50) {
    const img = this.page.locator('img[alt="Document"]')
    const box = await img.boundingBox()
    if (!box) return false

    await this.page.mouse.move(box.x + xOffset, box.y + yOffset)
    await this.page.mouse.down()
    await this.page.mouse.move(box.x + xOffset + width, box.y + yOffset + height)
    await this.page.mouse.up()
    await this.page.waitForTimeout(500)
    return true
  }

  /** Click Read Text on the selected region */
  async readSelectedRegion() {
    await this.page.getByRole('button', { name: 'Read Text' }).click()
    // Wait for Ollama response (up to 30s)
    await this.page.locator('text=/Text|Identified/').waitFor({ timeout: 30_000 })
  }

  /** Label the current result and save as training example */
  async labelAndSave(fieldName) {
    const select = this.page.locator('select').filter({ hasText: 'Field name' })
    await select.selectOption(fieldName)
    await this.page.getByRole('button', { name: 'Save Example' }).click()
    await this.page.waitForTimeout(1000)
  }

  /** Check training data panel is visible */
  async expectTrainingDataVisible() {
    await this.page.getByText('Training Data').waitFor({ timeout: 5000 })
  }
}
