import { waitForPageReady, waitForToast } from '../helpers/wait-helpers.js'

export class EFormsPage {
  constructor(page) { this.page = page }

  async goto() {
    await this.page.goto('/eforms')
    await waitForPageReady(this.page)
  }

  async openForm(formName) {
    await this.page.locator(`text="${formName}"`).first().click()
    // May navigate to /eforms/fill/:formKey
    await this.page.waitForTimeout(1000)
  }

  async fillField(key, value) {
    // Try by name, then by data-field-key, then by label
    const input = this.page.locator(`[name="${key}"], [data-field-key="${key}"]`).first()
    if (await input.isVisible().catch(() => false)) {
      const tagName = await input.evaluate(el => el.tagName.toLowerCase())
      if (tagName === 'select') {
        await input.selectOption({ label: value })
      } else if (tagName === 'textarea') {
        await input.fill(value)
      } else {
        await input.fill(value)
      }
      return
    }

    // Fallback: find by label text
    const label = this.page.locator(`label:has-text("${key}")`)
    if (await label.isVisible().catch(() => false)) {
      const fieldContainer = label.locator('..')
      const fieldInput = fieldContainer.locator('input, select, textarea').first()
      await fieldInput.fill(value)
    }
  }

  async selectDropdown(key, label) {
    const select = this.page.locator(`[name="${key}"], [data-field-key="${key}"]`).first()
    await select.selectOption({ label })
  }

  async checkCheckbox(key) {
    const checkbox = this.page.locator(`[name="${key}"], [data-field-key="${key}"]`).first()
    await checkbox.check()
  }

  async selectRadio(key, value) {
    const radio = this.page.locator(`input[type="radio"][name="${key}"][value="${value}"]`)
    await radio.click()
  }

  async clickNext() {
    await this.page.locator('button:has-text("Next")').click()
    await this.page.waitForTimeout(500)
  }

  async clickSubmit() {
    await this.page.locator('button:has-text("Submit"), button:has-text("Confirm")').last().click()
    await waitForToast(this.page, 'submitted', 15_000).catch(() => {})
  }

  async expectSubmissionSuccess() {
    // Look for success toast or confirmation page
    await this.page.locator('text=/submitted|success|thank you/i').waitFor({ timeout: 15_000 })
  }
}

export class FormDesignerPage {
  constructor(page) { this.page = page }

  async goto() {
    await this.page.goto('/eforms/designer/list')
    await waitForPageReady(this.page)
  }

  async getFormCount() {
    const rows = this.page.locator('table tbody tr')
    return rows.count()
  }

  async expectFormVisible(name) {
    await this.page.locator(`text="${name}"`).waitFor({ timeout: 10_000 })
  }
}
