import { OKTA } from '../helpers/selectors.js'

export class LoginPage {
  constructor(page) { this.page = page }

  async loginViaOkta(username, password) {
    const oktaDomain = process.env.OKTA_LOGIN_DOMAIN || 'okta.com'
    await this.page.waitForURL(url => url.hostname.includes(oktaDomain), { timeout: 30_000 })
    await this.page.locator(OKTA.usernameInput).fill(username)

    const nextBtn = this.page.locator(OKTA.nextButton)
    if (await nextBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
      await nextBtn.click()
      await this.page.locator(OKTA.passwordInput).waitFor({ timeout: 10_000 })
    }

    await this.page.locator(OKTA.passwordInput).fill(password)
    await this.page.locator(OKTA.submitButton).click()
    await this.page.waitForURL(url => !url.hostname.includes(oktaDomain), { timeout: 30_000 })
    await this.page.waitForURL('**/dashboard', { timeout: 30_000 })
    await this.page.waitForLoadState('networkidle')
  }
}
