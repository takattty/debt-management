import { test as base } from '@playwright/test';

export const test = base.extend({
  page: async ({ page }, provide, testInfo) => {
    await provide(page);
    if (testInfo.status === testInfo.expectedStatus || page.isClosed()) return;
    // Prevent Playwright's automatic DOM snapshot from serializing readonly bearer links.
    await testInfo.attach('error-context', { body: 'Sensitive form values omitted. See the masked failure screenshot and assertion details.', contentType: 'text/plain' });
    try {
      const screenshot = await page.screenshot({ fullPage: true, mask: [page.locator('[data-sensitive], input[type="password"]')] });
      await testInfo.attach('failure-screenshot', { body: screenshot, contentType: 'image/png' });
    } catch { /* Preserve the original failure when the page cannot be captured. */ }
  },
});
