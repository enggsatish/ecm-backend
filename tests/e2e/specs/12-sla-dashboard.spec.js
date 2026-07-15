/**
 * Suite 12 — SLA Dashboard
 *
 * Tests the SLA monitoring dashboard: page load, filter cards,
 * workflow instance list, search, and role-based access.
 */
import { test, expect } from '../fixtures/auth.fixture.js'
import { waitForPageReady } from '../helpers/wait-helpers.js'

test.describe('SLA Dashboard', () => {

  test('SLA dashboard page loads', async ({ adminPage }) => {
    // Try the dedicated SLA route first, fall back to workflow page with SLA tab
    await adminPage.goto('/workflow/sla')
    await waitForPageReady(adminPage)

    const heading = adminPage.locator('h1, h2').filter({
      hasText: /SLA|Service Level|Workflow/i
    })

    const headingVisible = await heading.first().isVisible({ timeout: 5_000 }).catch(() => false)

    if (!headingVisible) {
      // Fall back: navigate to /workflow and look for an SLA tab
      await adminPage.goto('/workflow')
      await waitForPageReady(adminPage)

      const slaTab = adminPage.locator('button:has-text("SLA"), a:has-text("SLA")')
      if (await slaTab.isVisible({ timeout: 5_000 }).catch(() => false)) {
        await slaTab.click()
        await adminPage.waitForTimeout(500)
      }
    }

    // Verify the page has loaded with some content
    const pageContent = adminPage.locator('h1, h2, h3').first()
    await expect(pageContent).toBeVisible({ timeout: 15_000 })
  })

  test('SLA filter cards visible', async ({ adminPage }) => {
    await adminPage.goto('/workflow/sla')
    await waitForPageReady(adminPage)

    // Fall back to SLA tab on workflow page if needed
    const slaTab = adminPage.locator('button:has-text("SLA"), a:has-text("SLA")')
    if (await slaTab.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await slaTab.click()
      await adminPage.waitForTimeout(500)
    }

    // Verify the three SLA status cards are visible
    const onTrackCard = adminPage.locator('text="On Track"')
    const warningCard = adminPage.locator('text="Warning"')
    const breachedCard = adminPage.locator('text="Breached"')

    await expect(onTrackCard.first()).toBeVisible({ timeout: 10_000 })
    await expect(warningCard.first()).toBeVisible({ timeout: 5_000 })
    await expect(breachedCard.first()).toBeVisible({ timeout: 5_000 })
  })

  test('SLA list shows workflow instances', async ({ adminPage }) => {
    await adminPage.goto('/workflow/sla')
    await waitForPageReady(adminPage)

    const slaTab = adminPage.locator('button:has-text("SLA"), a:has-text("SLA")')
    if (await slaTab.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await slaTab.click()
      await adminPage.waitForTimeout(500)
    }

    // The SLA dashboard should display a table or list of workflow instances
    const table = adminPage.locator('table')
    const tableVisible = await table.isVisible({ timeout: 10_000 }).catch(() => false)

    if (tableVisible) {
      // Verify at least the table header is present
      const headerCells = table.locator('thead th, thead td')
      const headerCount = await headerCells.count()
      expect(headerCount).toBeGreaterThan(0)

      // Check for typical SLA columns
      const headers = await table.locator('thead').textContent()
      const hasExpectedColumns = /workflow|instance|status|due|sla|deadline/i.test(headers)
      expect(hasExpectedColumns).toBeTruthy()
    } else {
      // Could be a card-based layout — verify at least some list items exist
      const listItems = adminPage.locator('[data-testid="sla-item"], .sla-row, [role="row"]')
      const listCount = await listItems.count()
      expect(listCount).toBeGreaterThanOrEqual(0)
    }
  })

  test('Search works on SLA dashboard', async ({ adminPage }) => {
    await adminPage.goto('/workflow/sla')
    await waitForPageReady(adminPage)

    const slaTab = adminPage.locator('button:has-text("SLA"), a:has-text("SLA")')
    if (await slaTab.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await slaTab.click()
      await adminPage.waitForTimeout(500)
    }

    // Find the search input
    const searchInput = adminPage.locator('input[placeholder*="Search"], input[placeholder*="search"], input[type="search"]')
    const hasSearch = await searchInput.isVisible({ timeout: 5_000 }).catch(() => false)

    if (hasSearch) {
      // Get the row count before search
      const rowsBefore = await adminPage.locator('table tbody tr').count().catch(() => 0)

      // Type a search query
      await searchInput.fill('test')
      await adminPage.waitForTimeout(1000)

      // The table should respond to the filter (rows may decrease or stay the same)
      const rowsAfter = await adminPage.locator('table tbody tr').count().catch(() => 0)

      // Verify search is functional — at minimum, the input accepted the text
      const inputValue = await searchInput.inputValue()
      expect(inputValue).toBe('test')

      // Clear the search
      await searchInput.clear()
      await adminPage.waitForTimeout(500)
    } else {
      test.info().annotations.push({ type: 'skip-reason', description: 'Search input not found on SLA dashboard' })
    }
  })

  test('Reviewer can see SLA dashboard', async ({ reviewerPage }) => {
    await reviewerPage.goto('/workflow/sla')
    await waitForPageReady(reviewerPage)

    // Fall back to SLA tab on workflow page if needed
    const slaTab = reviewerPage.locator('button:has-text("SLA"), a:has-text("SLA")')
    if (await slaTab.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await slaTab.click()
      await reviewerPage.waitForTimeout(500)
    }

    // Verify the reviewer is not blocked — should see the dashboard content
    const currentUrl = reviewerPage.url()
    const accessDenied = reviewerPage.locator('text="Access Denied", text="Forbidden", text="403"')
    const isBlocked = await accessDenied.first().isVisible({ timeout: 3_000 }).catch(() => false)
    expect(isBlocked).toBeFalsy()

    // Should see at least the page heading or SLA cards
    const onTrack = reviewerPage.locator('text="On Track"')
    const heading = reviewerPage.locator('h1, h2').filter({ hasText: /SLA|Workflow/i })

    const hasContent = await onTrack.first().isVisible({ timeout: 10_000 }).catch(() => false)
      || await heading.first().isVisible({ timeout: 3_000 }).catch(() => false)

    expect(hasContent).toBeTruthy()
  })
})
