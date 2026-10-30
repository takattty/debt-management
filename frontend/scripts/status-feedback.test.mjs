import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { ESLint } from 'eslint';

const eslint = new ESLint({ cwd: fileURLToPath(new URL('../', import.meta.url)) });
const fixture = (expression) => `import type { Page } from '@playwright/test'; export const sample = (page: Page) => ${expression};`;
const messages = async (source, filePath = 'e2e/status-lint-fixture.ts') =>
  (await eslint.lintText(source, { filePath }))[0].messages.filter((message) => message.ruleId === 'no-restricted-syntax');

test('known RED: direct status calls, including pre-filtered calls, fail the E2E lint rule', async () => {
  for (const expression of ["page.getByRole('status')", "page.getByRole(\"status\").filter({hasText:'done'})", "page['getByRole']('status')", 'page.getByRole(`status`)']) {
    const rejected = await messages(fixture(expression));
    assert.equal(rejected.length, 1, expression);
    assert.equal(rejected[0].severity, 2, expression);
  }
});

test('semantic page/region scope with string or RegExp uses the shared helper', async () => {
  const source = `import { expect, type Page } from '@playwright/test'; import { statusFeedback } from './status-feedback';
    export async function sample(page: Page) {
      await expect(statusFeedback(page, 'saved')).toContainText('saved');
      await expect(statusFeedback(page.getByRole('region', { name: 'operation' }), /saved/)).toBeVisible();
    }`;
  const result = await eslint.lintText(source, { filePath: 'e2e/status-lint-fixture.ts' });
  assert.equal(result[0].errorCount, 0);
});

test('only the shared helper can construct a direct E2E status locator', async () => {
  assert.equal((await messages(fixture("page.getByRole('status')"), 'e2e/status-feedback.ts')).length, 0);
});

test('product and unit selectors remain outside the E2E-only rule', async () => {
  assert.equal((await messages(fixture("page.getByRole('status')"), 'src/status-lint-fixture.test.tsx')).length, 0);
});
