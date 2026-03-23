import { waitForPageReady, waitForToast } from '../helpers/wait-helpers.js'

export class WorkflowDesignerPage {
  constructor(page) { this.page = page }

  async goto() {
    await this.page.goto('/workflow/designer')
    await waitForPageReady(this.page)
  }

  async getTemplateCount() {
    const rows = this.page.locator('table tbody tr')
    return rows.count()
  }

  async expectTemplateVisible(name) {
    await this.page.locator(`text="${name}"`).waitFor({ timeout: 10_000 })
  }

  async createTemplate(name, slaHours = 48) {
    await this.page.locator('button:has-text("New Template")').click()
    await this.page.locator('input[placeholder*="Template"]').fill(name)

    const slaInput = this.page.locator('input[type="number"]').first()
    if (await slaInput.isVisible()) {
      await slaInput.fill(String(slaHours))
    }

    await this.page.locator('button:has-text("Create")').click()
    await waitForToast(this.page, 'created', 10_000).catch(() => {})
    await this.page.waitForTimeout(1000)
  }

  async openTemplate(name) {
    // Double-click the row to open the editor
    await this.page.locator(`tr:has-text("${name}")`).dblclick()
    await this.page.waitForTimeout(1000)
  }

  async publishTemplate(name) {
    // Open action menu for the template
    const row = this.page.locator(`tr:has-text("${name}")`)
    await row.locator('button').last().click()
    await this.page.waitForTimeout(300)
    await this.page.locator('button:has-text("Publish")').click()
    await waitForToast(this.page, 'published', 10_000).catch(() => {})
  }

  async closeEditor() {
    await this.page.locator('button:has(svg)').first().click() // X button
    await this.page.waitForTimeout(500)
  }

  // ── Category Mappings tab ──

  async goToMappingsTab() {
    await this.page.locator('button:has-text("Category Mappings")').click()
    await this.page.waitForTimeout(500)
  }

  async addCategoryMapping(categoryName, workflowName) {
    const categorySelect = this.page.locator('select').first()
    await categorySelect.selectOption({ label: categoryName })

    const workflowSelect = this.page.locator('select').nth(1)
    await workflowSelect.selectOption({ label: new RegExp(workflowName) })

    await this.page.locator('button:has-text("Add")').click()
    await waitForToast(this.page, 'created', 10_000).catch(() => {})
  }

  async expectMappingVisible(categoryName) {
    await this.page.locator(`td:has-text("${categoryName}")`).waitFor({ timeout: 10_000 })
  }

  async deleteMappingFor(categoryName) {
    const row = this.page.locator(`tr:has-text("${categoryName}")`)
    await row.locator('button').last().click() // Trash button
    await waitForToast(this.page, 'removed', 10_000).catch(() => {})
  }
}
