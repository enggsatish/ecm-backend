import { waitForPageReady } from '../helpers/wait-helpers.js'

export class AdminPage {
  constructor(page) { this.page = page }

  async goto(subPath = '/users') {
    await this.page.goto(`/admin${subPath}`)
    await waitForPageReady(this.page)
  }

  async expectHeading(text) {
    await this.page.locator(`h1:has-text("${text}"), h2:has-text("${text}")`).waitFor({ timeout: 10_000 })
  }

  async getUserCount() {
    const rows = this.page.locator('table tbody tr')
    return rows.count()
  }

  async expectAccessDenied() {
    // RoleGuard shows "Not Authorised" or similar
    await this.page.locator('text=/not authorized|not authorised|access denied|forbidden/i').waitFor({ timeout: 5000 })
  }
}
