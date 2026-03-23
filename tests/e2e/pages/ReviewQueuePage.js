import { waitForPageReady, waitForToast } from '../helpers/wait-helpers.js'

export class ReviewQueuePage {
  constructor(page) { this.page = page }

  async goto() {
    await this.page.goto('/backoffice/queue')
    await waitForPageReady(this.page)
  }

  async switchTab(tabName) {
    await this.page.locator(`button:has-text("${tabName}")`).click()
    await this.page.waitForTimeout(500)
  }

  async getTaskCount() {
    const rows = this.page.locator('table tbody tr, [data-testid="task-row"]')
    return rows.count()
  }

  async expectTaskVisible(taskName) {
    await this.page.locator(`text="${taskName}"`).waitFor({ timeout: 15_000 })
  }

  async claimTask(taskName) {
    const row = this.page.locator(`tr:has-text("${taskName}"), div:has-text("${taskName}")`).first()
    await row.locator('button:has-text("Claim")').click()
    await waitForToast(this.page, 'claimed', 10_000).catch(() => {})
  }

  async approveTask(taskName, comment = '') {
    const row = this.page.locator(`tr:has-text("${taskName}"), div:has-text("${taskName}")`).first()
    await row.locator('button:has-text("Approve")').click()
    await this.page.waitForTimeout(500)

    if (comment) {
      const textarea = this.page.locator('textarea').last()
      if (await textarea.isVisible()) await textarea.fill(comment)
    }

    // Confirm in modal if present
    const confirmBtn = this.page.locator('button:has-text("Confirm")')
    if (await confirmBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
      await confirmBtn.click()
    }
    await waitForToast(this.page, 'approved', 10_000).catch(() => {})
  }

  async rejectTask(taskName, comment) {
    const row = this.page.locator(`tr:has-text("${taskName}"), div:has-text("${taskName}")`).first()
    await row.locator('button:has-text("Reject")').click()
    await this.page.waitForTimeout(500)

    if (comment) {
      await this.page.locator('textarea').last().fill(comment)
    }

    const confirmBtn = this.page.locator('button:has-text("Confirm")')
    if (await confirmBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
      await confirmBtn.click()
    }
    await waitForToast(this.page, 'rejected', 10_000).catch(() => {})
  }

  async requestInfo(taskName, comment) {
    const row = this.page.locator(`tr:has-text("${taskName}"), div:has-text("${taskName}")`).first()
    await row.locator('button:has-text("Request Info")').click()
    await this.page.waitForTimeout(500)

    if (comment) {
      await this.page.locator('textarea').last().fill(comment)
    }

    const confirmBtn = this.page.locator('button:has-text("Confirm")')
    if (await confirmBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
      await confirmBtn.click()
    }
  }
}
