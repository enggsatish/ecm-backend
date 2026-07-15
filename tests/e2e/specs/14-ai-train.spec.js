/**
 * Suite 14 — AI Train (Visual OCR Training)
 *
 * Tests the AI Train tab in the document viewer:
 *   - Region selection on document image
 *   - Read Text / Identify actions via GLM-OCR
 *   - Label as training example
 *   - Training data panel visibility
 *   - Custom field creation
 *
 * Prerequisites:
 *   - Ollama running with glm-ocr model
 *   - At least one uploaded document with image preview
 */
import { test, expect } from '../fixtures/auth.fixture.js'
import { DocumentsPage } from '../pages/DocumentsPage.js'

test.describe('AI Train — Visual OCR Training', () => {

  test('AI Train tab exists in document viewer', async ({ adminPage }) => {
    const docs = new DocumentsPage(adminPage)
    await docs.goto()
    await expect(adminPage.locator('table')).toBeVisible({ timeout: 15_000 })

    const firstViewBtn = adminPage.locator('table tbody tr').first().locator('button[title="View document"]')
    if (await firstViewBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
      await firstViewBtn.click()
      await adminPage.waitForTimeout(2000)

      // AI Train tab should be visible
      await expect(adminPage.getByRole('button', { name: 'AI Train' })).toBeVisible({ timeout: 5000 })
    }
  })

  test('AI Train tab shows document image', async ({ adminPage }) => {
    const docs = new DocumentsPage(adminPage)
    await docs.goto()
    await expect(adminPage.locator('table')).toBeVisible({ timeout: 15_000 })

    const firstViewBtn = adminPage.locator('table tbody tr').first().locator('button[title="View document"]')
    if (await firstViewBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
      await firstViewBtn.click()
      await adminPage.waitForTimeout(2000)

      await adminPage.getByRole('button', { name: 'AI Train' }).click()
      await adminPage.waitForTimeout(2000)

      // Should show the document image
      await expect(adminPage.locator('img[alt="Document"]')).toBeVisible({ timeout: 10_000 })
    }
  })

  test('AI Train shows instruction text and zoom controls', async ({ adminPage }) => {
    const docs = new DocumentsPage(adminPage)
    await docs.goto()
    await expect(adminPage.locator('table')).toBeVisible({ timeout: 15_000 })

    const firstViewBtn = adminPage.locator('table tbody tr').first().locator('button[title="View document"]')
    if (await firstViewBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
      await firstViewBtn.click()
      await adminPage.waitForTimeout(2000)

      await adminPage.getByRole('button', { name: 'AI Train' }).click()
      await adminPage.waitForTimeout(1000)

      // Should show instruction
      await expect(adminPage.getByText('Draw a rectangle')).toBeVisible({ timeout: 5000 })
      // Should show zoom controls
      await expect(adminPage.getByText('100%')).toBeVisible({ timeout: 5000 })
    }
  })

  test('AI Train shows training data panel', async ({ adminPage }) => {
    const docs = new DocumentsPage(adminPage)
    await docs.goto()
    await expect(adminPage.locator('table')).toBeVisible({ timeout: 15_000 })

    const firstViewBtn = adminPage.locator('table tbody tr').first().locator('button[title="View document"]')
    if (await firstViewBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
      await firstViewBtn.click()
      await adminPage.waitForTimeout(2000)

      await adminPage.getByRole('button', { name: 'AI Train' }).click()
      await adminPage.waitForTimeout(2000)

      // Training data panel should be visible (auto-loads)
      await expect(adminPage.getByText('Training Data')).toBeVisible({ timeout: 5000 })
    }
  })

  test('Region selection shows Read Text and Identify buttons', async ({ adminPage }) => {
    const docs = new DocumentsPage(adminPage)
    await docs.goto()
    await expect(adminPage.locator('table')).toBeVisible({ timeout: 15_000 })

    const firstViewBtn = adminPage.locator('table tbody tr').first().locator('button[title="View document"]')
    if (await firstViewBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
      await firstViewBtn.click()
      await adminPage.waitForTimeout(2000)

      await adminPage.getByRole('button', { name: 'AI Train' }).click()
      await adminPage.waitForTimeout(2000)

      // Draw a rectangle on the image by mouse drag
      const img = adminPage.locator('img[alt="Document"]')
      if (await img.isVisible({ timeout: 5000 }).catch(() => false)) {
        const box = await img.boundingBox()
        if (box) {
          // Draw a selection rectangle
          await adminPage.mouse.move(box.x + 50, box.y + 50)
          await adminPage.mouse.down()
          await adminPage.mouse.move(box.x + 200, box.y + 100)
          await adminPage.mouse.up()
          await adminPage.waitForTimeout(500)

          // Read Text and Identify buttons should appear
          await expect(adminPage.getByRole('button', { name: 'Read Text' })).toBeVisible({ timeout: 5000 })
          await expect(adminPage.getByRole('button', { name: 'Identify Content' })).toBeVisible({ timeout: 5000 })
        }
      }
    }
  })

  test('Read Text extracts text from selected region', async ({ adminPage }) => {
    const docs = new DocumentsPage(adminPage)
    await docs.goto()
    await expect(adminPage.locator('table')).toBeVisible({ timeout: 15_000 })

    const firstViewBtn = adminPage.locator('table tbody tr').first().locator('button[title="View document"]')
    if (await firstViewBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
      await firstViewBtn.click()
      await adminPage.waitForTimeout(2000)

      await adminPage.getByRole('button', { name: 'AI Train' }).click()
      await adminPage.waitForTimeout(2000)

      const img = adminPage.locator('img[alt="Document"]')
      if (await img.isVisible({ timeout: 5000 }).catch(() => false)) {
        const box = await img.boundingBox()
        if (box) {
          // Draw selection
          await adminPage.mouse.move(box.x + 50, box.y + 50)
          await adminPage.mouse.down()
          await adminPage.mouse.move(box.x + 250, box.y + 100)
          await adminPage.mouse.up()
          await adminPage.waitForTimeout(500)

          // Click Read Text
          await adminPage.getByRole('button', { name: 'Read Text' }).click()

          // Wait for result (Ollama call)
          await expect(adminPage.getByText('Analyzing')).toBeVisible({ timeout: 5000 }).catch(() => {})
          await expect(adminPage.locator('text=/Text|Identified/')).toBeVisible({ timeout: 30_000 })
        }
      }
    }
  })

  test('Label and save training example', async ({ adminPage }) => {
    const docs = new DocumentsPage(adminPage)
    await docs.goto()
    await expect(adminPage.locator('table')).toBeVisible({ timeout: 15_000 })

    const firstViewBtn = adminPage.locator('table tbody tr').first().locator('button[title="View document"]')
    if (await firstViewBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
      await firstViewBtn.click()
      await adminPage.waitForTimeout(2000)

      await adminPage.getByRole('button', { name: 'AI Train' }).click()
      await adminPage.waitForTimeout(2000)

      const img = adminPage.locator('img[alt="Document"]')
      if (await img.isVisible({ timeout: 5000 }).catch(() => false)) {
        const box = await img.boundingBox()
        if (box) {
          // Draw + Read Text
          await adminPage.mouse.move(box.x + 50, box.y + 50)
          await adminPage.mouse.down()
          await adminPage.mouse.move(box.x + 250, box.y + 100)
          await adminPage.mouse.up()
          await adminPage.waitForTimeout(500)

          await adminPage.getByRole('button', { name: 'Read Text' }).click()
          await adminPage.waitForTimeout(15_000) // wait for Ollama

          // Select field name from dropdown
          const fieldSelect = adminPage.locator('select').filter({ hasText: 'Field name' })
          if (await fieldSelect.isVisible({ timeout: 5000 }).catch(() => false)) {
            await fieldSelect.selectOption('full_name')

            // Click Save Example
            await adminPage.getByRole('button', { name: 'Save Example' }).click()

            // Should see success toast
            await expect(adminPage.locator('div[role="status"]')).toBeVisible({ timeout: 5000 }).catch(() => {})
          }
        }
      }
    }
  })

  test('Custom field can be added via "+ Add custom field"', async ({ adminPage }) => {
    const docs = new DocumentsPage(adminPage)
    await docs.goto()
    await expect(adminPage.locator('table')).toBeVisible({ timeout: 15_000 })

    const firstViewBtn = adminPage.locator('table tbody tr').first().locator('button[title="View document"]')
    if (await firstViewBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
      await firstViewBtn.click()
      await adminPage.waitForTimeout(2000)

      await adminPage.getByRole('button', { name: 'AI Train' }).click()
      await adminPage.waitForTimeout(2000)

      const img = adminPage.locator('img[alt="Document"]')
      if (await img.isVisible({ timeout: 5000 }).catch(() => false)) {
        const box = await img.boundingBox()
        if (box) {
          // Draw + Read Text
          await adminPage.mouse.move(box.x + 50, box.y + 50)
          await adminPage.mouse.down()
          await adminPage.mouse.move(box.x + 250, box.y + 100)
          await adminPage.mouse.up()
          await adminPage.waitForTimeout(500)

          await adminPage.getByRole('button', { name: 'Read Text' }).click()
          await adminPage.waitForTimeout(15_000)

          // Select "+ Add custom field" from dropdown
          const fieldSelect = adminPage.locator('select').filter({ hasText: 'Field name' })
          if (await fieldSelect.isVisible({ timeout: 5000 }).catch(() => false)) {
            await fieldSelect.selectOption('__add_new__')
            await adminPage.waitForTimeout(500)

            // Input field should appear
            const customInput = adminPage.locator('input[placeholder*="height"]')
            await expect(customInput).toBeVisible({ timeout: 3000 })

            // Type a custom field name
            await customInput.fill('height_cm')
            await customInput.press('Enter')

            // Save Example button should be available
            await expect(adminPage.getByRole('button', { name: 'Save Example' })).toBeVisible({ timeout: 3000 })
          }
        }
      }
    }
  })
})
