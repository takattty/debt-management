import { appendFile, mkdir, mkdtemp, rm } from 'node:fs/promises';
import { strict as assert } from 'node:assert';
import { join } from 'node:path';
import process from 'node:process';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';

test('generation check rejects stale output without rewriting it', async () => {
  await mkdir('work', { recursive: true });
  const directory = await mkdtemp('work/api-drift-');
  const output = join(directory, 'schema.ts');
  const run = (...args) => {
    const result = spawnSync(process.execPath, ['scripts/generate-api.mjs', '--output', output, ...args], { encoding: 'utf8' });
    assert.ifError(result.error);
    return result;
  };
  try {
    const generated = run();
    assert.equal(generated.status, 0, generated.stderr);
    const current = run('--check');
    assert.equal(current.status, 0, current.stderr);
    await appendFile(output, '\n// Deliberate stale generation probe.\n');
    const stale = run('--check');
    assert.equal(stale.status, 1);
    assert.match(stale.stderr, /OpenAPI types are stale/);
    assert.equal(run('--check').status, 1, 'check must not silently regenerate stale output');
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
