import { waitForPageReady, waitForToast, pollUntil } from '../helpers/wait-helpers.js'
import path from 'path'

export class DocumentsPage {
  constructor(page) { this.page = page }

  async goto() {
    await this.page.goto('/documents')
    await waitForPageReady(this.page)
  }

  async uploadDocument(filePath, categoryName) {
    // Open upload panel if collapsed
    const uploadBtn = this.page.locator('button:has-text("Upload")')
    if (await uploadBtn.isVisible()) await uploadBtn.click()

    // Select category
    if (categoryName) {
      const categorySelect = this.page.locator('select').filter({ hasText: 'Category' }).first()
        || this.page.locator('select').first()
      if (await categorySelect.isVisible().catch(() => false)) {
        await categorySelect.selectOption({ label: categoryName })
      }
    }

    // Upload file
    const fileInput = this.page.locator('input[type="file"]')
    await fileInput.setInputFiles(filePath)

    // Wait for upload completion
    await waitForToast(this.page, 'uploaded', 15_000).catch(() => {})
    await this.page.waitForTimeout(1000)
  }

  async getDocumentCount() {
    const rows = this.page.locator('table tbody tr')
    return rows.count()
  }

  async openDocumentViewer(documentName) {
    const row = this.page.locator(`tr:has-text("${documentName}")`)
    await row.locator('button').filter({ has: this.page.locator('svg') }).first().click()
  }

  async waitForOcrComplete(documentName, timeout = 120_000) {
    return pollUntil(this.page, async () => {
      const row = this.page.locator(`tr:has-text("${documentName}")`)
      const status = await row.locator('text=/OCR_COMPLETED|ACTIVE/').isVisible().catch(() => false)
      return status
    }, { interval: 5000, timeout })
  }

  async expectDocumentStatus(documentName, status) {
    await this.page.locator(`tr:has-text("${documentName}") >> text="${status}"`).waitFor({ timeout: 10_000 })
  }

  // Document viewer modal
  async expectViewerOpen() {
    await this.page.locator('text="Preview"').waitFor({ timeout: 5000 })
  }

  async selectViewerTab(tabName) {
    await this.page.locator(`button:has-text("${tabName}")`).click()
  }

  async expectReviewStatus(status) {
    await this.page.locator(`text="${status}"`).waitFor({ timeout: 10_000 })
  }

  async closeViewer() {
    await this.page.keyboard.press('Escape')
  }
}
