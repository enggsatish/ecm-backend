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

    const customerInput = this.page.locator('input[placeholder*="customer"], input[placeholder*="Customer"], input[placeholder*="search"]').first()
    if (await customerInput.isVisible()) {
      await customerInput.fill(customerName)
      await this.page.waitForTimeout(1000)
      await this.page.locator(`text="${customerName}"`).first().click()
    }

    const productSelect = this.page.locator('select').filter({ hasText: /product/i }).first()
      || this.page.locator('select').nth(0)
    if (await productSelect.isVisible().catch(() => false)) {
      await productSelect.selectOption({ label: product })
    }

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

  /** Check the Owner column badge text for a case row */
  async getOwnerText(caseRef) {
    const row = this.page.locator(`tr:has-text("${caseRef}")`)
    const ownerCell = row.locator('td').nth(4) // Owner is typically 5th column
    return ownerCell.textContent()
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

  /** Click "Start Working" to transition NEW → IN_PROGRESS and claim the case */
  async startWorking() {
    await this.page.locator('button:has-text("Start Working")').click()
    await waitForToast(this.page, 'updated', 10_000).catch(() => {})
    await this.page.waitForTimeout(500)
  }

  /** Get the owner display from the detail header */
  async getOwnerDisplay() {
    const ownerSection = this.page.locator('text="Owner"').locator('..')
    return ownerSection.textContent()
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

  /** Click the eye icon on a checklist item to open document viewer */
  async viewChecklistDocument(itemName) {
    const item = this.page.locator(`text="${itemName}"`).locator('..')
    await item.locator('button[title="View document"], button:has(svg.lucide-eye)').click()
    await this.page.waitForTimeout(1000)
  }

  /** Click "Actions" dropdown on a checklist item */
  async openActionsMenu(itemName) {
    const item = this.page.locator(`text="${itemName}"`).locator('..')
    await item.locator('button:has-text("Actions")').click()
    await this.page.waitForTimeout(300)
  }

  /** Mark a checklist item as complete via Actions menu */
  async markComplete(itemName) {
    await this.openActionsMenu(itemName)
    await this.page.locator('button:has-text("Mark as Complete")').click()
    await waitForToast(this.page, 'complete', 10_000).catch(() => {})
  }

  /** Reopen a completed checklist item */
  async reopenItem(itemName) {
    const item = this.page.locator(`text="${itemName}"`).locator('..')
    await item.locator('button:has-text("Reopen")').click()
    await waitForToast(this.page, 'reopened', 10_000).catch(() => {})
  }

  /** Start workflow for a checklist item via Actions menu */
  async startWorkflow(itemName) {
    await this.openActionsMenu(itemName)
    await this.page.locator('button:has-text("Start Workflow")').click()
    await waitForToast(this.page, 'Workflow started', 10_000).catch(() => {})
  }

  /** Open Send for Signature modal via Actions menu */
  async openSendForSignatureModal(itemName) {
    await this.openActionsMenu(itemName)
    await this.page.locator('button:has-text("Send for Signature")').click()
    await this.page.waitForTimeout(500)
  }

  /** Fill and submit the Send for Signature modal */
  async sendForSignature(itemName, { email, name, placement = 'lastPage' }) {
    await this.openSendForSignatureModal(itemName)
    await this.page.locator('input[type="email"]').fill(email)
    await this.page.locator('input[placeholder*="John Smith"]').fill(name)
    if (placement !== 'lastPage') {
      await this.page.locator(`input[value="${placement}"]`).check()
    }
    await this.page.locator('button:has-text("Send for Signature")').last().click()
    await waitForToast(this.page, 'signature', 15_000).catch(() => {})
  }

  /** Add a new checklist item */
  async addDocumentRequest({ categoryName, customName, isRequired = false }) {
    await this.page.locator('button:has-text("Add Document Request")').click()
    await this.page.waitForTimeout(300)

    if (categoryName) {
      await this.page.locator('button:has-text("From Category")').click()
      await this.page.locator('select').last().selectOption({ label: categoryName })
    } else if (customName) {
      await this.page.locator('button:has-text("Custom Name")').click()
      await this.page.locator('input[placeholder*="Power of Attorney"]').fill(customName)
    }

    if (isRequired) {
      await this.page.locator('input[type="checkbox"]').last().check()
    }

    await this.page.locator('button:has-text("Add to Checklist")').click()
    await waitForToast(this.page, 'added', 10_000).catch(() => {})
  }

  /** Check if a checklist item shows "Awaiting Signature" badge */
  async expectAwaitingSignature(itemName) {
    const item = this.page.locator(`text="${itemName}"`).locator('..')
    await item.locator('text="Awaiting Signature"').waitFor({ timeout: 10_000 })
  }

  /** Check if a checklist item shows "Completed" state */
  async expectCompleted(itemName) {
    const item = this.page.locator(`text="${itemName}"`).locator('..')
    await item.locator('text="Completed"').waitFor({ timeout: 10_000 })
  }

  /** Check actions are disabled (case is NEW) */
  async expectActionsDisabled(itemName) {
    const item = this.page.locator(`text="${itemName}"`).locator('..')
    const hint = item.locator('text="Start working to enable actions"')
    return hint.isVisible()
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
    const badge = item.locator('span').filter({ hasText: /PENDING|UPLOADED|APPROVED|REJECTED|WAIVED|PENDING_SIGNATURE|SIGNED/ })
    return badge.textContent()
  }
}
