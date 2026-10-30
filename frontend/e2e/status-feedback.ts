import type { Locator, Page } from '@playwright/test';
export const statusFeedback = (scope: Page | Locator, expected: string | RegExp): Locator =>
  scope.getByRole('status').filter({ hasText: expected });
