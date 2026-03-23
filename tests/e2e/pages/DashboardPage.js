import { waitForPageReady } from '../helpers/wait-helpers.js'

export class DashboardPage {
  constructor(page) { this.page = page }

  async goto() {
    await this.page.goto('/dashboard')
    await waitForPageReady(this.page)
  }

  async expectGreeting(nameFragment) {
    await this.page.locator(`text=/${nameFragment}/i`).waitFor({ timeout: 10_000 })
  }

  async expectStatVisible(label) {
    await this.page.locator(`text="${label}"`).waitFor({ timeout: 10_000 })
  }

  async getSidebarLinks() {
    const links = this.page.locator('aside a, aside button')
    return links.allTextContents()
  }
}
