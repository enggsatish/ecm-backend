import { waitForPageReady, waitForToast } from '../helpers/wait-helpers.js'

export class CasesPage {
  constructor(page) { this.page = page }

  async goto() {
    await this.page.goto('/cases')
    await waitForPageReady(this.page)
  }

  async createCase({ customerName, product, caseType = 'NEW_ACCOUNT' }) {
    await this.page.locator('button:has-text("New Case"), button:has-text("New Application")').click()
    await this.page.waitForTimeout(500)

    // Search and select customer
    const customerInput = this.page.locator('input[placeholder*="customer"], input[placeholder*="Customer"], input[placeholder*="search"]').first()
    if (await customerInput.isVisible()) {
      await customerInput.fill(customerName)
      await this.page.waitForTimeout(1000)
      await this.page.locator(`text="${customerName}"`).first().click()
    }

    // Select product
    const productSelect = this.page.locator('select').filter({ hasText: /product/i }).first()
      || this.page.locator('select').nth(0)
    if (await productSelect.isVisible().catch(() => false)) {
      await productSelect.selectOption({ label: product })
    }

    // Submit
    await this.page.locator('button:has-text("Create"), button:has-text("Submit")').click()
    await waitForToast(this.page, 'created', 10_000).catch(() => {})
    await this.page.waitForTimeout(1000)
  }

  async openCase(caseRef) {
    await this.page.locator(`text="${caseRef}"`).first().click()
    await waitForPageReady(this.page)
  }

  async getCaseCount() {
    const rows = this.page.locator('table tbody tr, [data-testid="case-row"]')
    return rows.count()
  }
}

export class CaseDetailPage {
  constructor(page) { this.page = page }

  async goto(caseId) {
    await this.page.goto(`/cases/${caseId}`)
    await waitForPageReady(this.page)
  }

  async expectStatus(status) {
    await this.page.locator(`text="${status}"`).first().waitFor({ timeout: 10_000 })
  }

  async uploadToChecklist(itemName, filePath) {
    const item = this.page.locator(`text="${itemName}"`).locator('..')
    const fileInput = item.locator('input[type="file"]')
    await fileInput.setInputFiles(filePath)
    await waitForToast(this.page, 'uploaded', 15_000).catch(() => {})
  }

  async fillFormForChecklist(itemName) {
    const item = this.page.locator(`text="${itemName}"`).locator('..')
    await item.locator('button:has-text("Fill Form")').click()
    await this.page.waitForURL('**/eforms/fill/**', { timeout: 10_000 })
  }

  async assignToGroup(groupName) {
    await this.page.locator('button:has-text("Assign")').click()
    await this.page.waitForTimeout(500)
    const groupSelect = this.page.locator('select').filter({ hasText: /group/i }).first()
      || this.page.locator('select').last()
    if (await groupSelect.isVisible().catch(() => false)) {
      await groupSelect.selectOption({ label: groupName })
    }
    await this.page.locator('button:has-text("Confirm"), button:has-text("Assign")').last().click()
    await waitForToast(this.page, 'assigned', 10_000).catch(() => {})
  }

  async claim() {
    await this.page.locator('button:has-text("Claim")').click()
    await waitForToast(this.page, 'claimed', 10_000).catch(() => {})
  }

  async changeStatus(status, comment) {
    await this.page.locator(`button:has-text("${status}")`).click()
    if (comment) {
      await this.page.locator('textarea').fill(comment)
    }
    await this.page.locator('button:has-text("Confirm"), button:has-text("Save")').click()
    await waitForToast(this.page, 'updated', 10_000).catch(() => {})
  }

  async verifyChecklistItem(itemName) {
    const item = this.page.locator(`text="${itemName}"`).locator('..')
    const checkbox = item.locator('input[type="checkbox"]')
    if (await checkbox.isVisible()) await checkbox.check()
  }

  async getChecklistItemStatus(itemName) {
    const item = this.page.locator(`text="${itemName}"`).locator('..')
    const badge = item.locator('span').filter({ hasText: /PENDING|UPLOADED|APPROVED|REJECTED|WAIVED/ })
    return badge.textContent()
  }
}
