import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import process from 'node:process';
import { test } from 'node:test';
import { chromium } from '@playwright/test';

// Static production assets and synthetic credentials only. No API routing/interception:
// Vite HMR and Playwright's default Chromium flag can prevent native BFCache.
const dist = fileURLToPath(new URL('../dist/', import.meta.url));
const fakeToken = 'A'.repeat(43);

async function fixture(authenticated) {
  const state = { inspections: 0 };
  const server = createServer(async (request, response) => {
    const pathname = new URL(request.url, 'http://fixture.invalid').pathname;
    const json = (body, status = 200) => {
      response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      response.end(JSON.stringify(body));
    };
    request.resume();
    if (pathname === '/api/session') return authenticated
      ? json({ userId: '11111111-1111-1111-1111-111111111101', tenantId: '11111111-1111-1111-1111-111111111111', email: 'admin@example.test', role: 'ADMIN', csrfToken: 'fixture', csrfHeaderName: 'X-CSRF-TOKEN' })
      : json({ code: 'UNAUTHENTICATED' }, 401);
    if (pathname === '/api/customers') return json([]);
    if (pathname === '/api/csrf') return json({ csrfToken: 'fixture', csrfHeaderName: 'X-CSRF-TOKEN' });
    if (pathname === '/api/invitations') return json({ id: '11111111-1111-1111-1111-111111111199', expiresAt: '2027-10-30T12:00:00Z', acceptanceUrl: `/accept-invitation#token=${fakeToken}` }, 201);
    if (pathname === '/api/invitations/inspect') {
      state.inspections++;
      return json({ tenantDisplayName: 'Synthetic tenant', maskedEmail: '***@example.test', role: 'ACCOUNTANT', expiresAt: '2027-10-30T12:00:00Z' });
    }
    if (pathname === '/away') {
      response.writeHead(200, { 'Content-Type': 'text/html' });
      return response.end('<!doctype html><title>Away</title><p>Another local document</p>');
    }
    const file = pathname === '/' || pathname === '/accept-invitation' ? resolve(dist, 'index.html') : resolve(dist, '.' + pathname);
    if (!file.startsWith(dist.endsWith(sep) ? dist : dist + sep)) { response.writeHead(404); return response.end(); }
    try {
      const body = await readFile(file);
      const content = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' }[extname(file)] ?? 'application/octet-stream';
      response.writeHead(200, { 'Content-Type': content }); response.end(body);
    } catch { response.writeHead(404); response.end(); }
  });
  await new Promise((resolveListen, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolveListen); });
  return { state, origin: `http://127.0.0.1:${server.address().port}`, close: () => new Promise((done) => { server.closeAllConnections(); server.close(done); }) };
}

async function exercise(authenticated) {
  const server = await fixture(authenticated);
  let browser;
  try {
    browser = await chromium.launch({
      headless: true,
      ...(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : { channel: 'chromium' }),
      args: ['--no-sandbox'], ignoreDefaultArgs: ['--disable-back-forward-cache'],
    });
    const page = await browser.newPage();
    await page.addInitScript(() => {
      window.__nativeLifecycle = { documentId: crypto.randomUUID(), restored: false, childCountAtHide: null };
      window.addEventListener('pageshow', (event) => { window.__nativeLifecycle.restored = event.persisted; });
    });
    await page.goto(server.origin + '/');
    if (authenticated) {
      await page.getByRole('button', { name: 'メンバー招待', exact: true }).click();
      await page.getByLabel('招待先メールアドレス', { exact: true }).fill('invitee@example.test');
      await page.getByRole('button', { name: '招待リンクを作成', exact: true }).click();
      await page.getByLabel('招待リンク', { exact: true }).waitFor();
    } else {
      await page.evaluate((target) => { setTimeout(() => window.location.assign(target), 0); }, server.origin + `/accept-invitation#token=${fakeToken}`);
      await page.getByLabel('招待されたメールアドレス', { exact: true }).fill('invitee@example.test');
      await page.getByLabel('新しいパスワード', { exact: true }).fill('synthetic-private-password');
    }
    const documentId = await page.evaluate(() => {
      // Registered after application startup: inspect DOM after its synchronous pagehide cleanup.
      window.addEventListener('pagehide', () => { window.__nativeLifecycle.childCountAtHide = document.getElementById('root').childElementCount; });
      return window.__nativeLifecycle.documentId;
    });
    await page.goto(server.origin + '/away');
    // A native BFCache activation may not emit a new commit/load signal. The
    // persisted/same-document assertions below are mandatory, including on timeout.
    await page.goBack({ waitUntil: 'commit', timeout: 3000 }).catch(() => {});
    const lifecycle = await page.evaluate(() => window.__nativeLifecycle);
    assert.equal(lifecycle.restored, true, 'must exercise a native persisted pageshow, not a reload');
    assert.equal(lifecycle.documentId, documentId, 'must restore the same browser document');
    assert.equal(lifecycle.childCountAtHide, 0, 'pagehide must synchronously clear the application DOM');
    if (authenticated) await page.getByRole('heading', { name: '顧客を登録', exact: true }).waitFor();
    else await page.getByRole('alert').waitFor();
    assert.equal(await page.getByLabel('招待リンク', { exact: true }).count(), 0, 'issued bearer link must not be redisplayed');
    assert.equal(await page.getByLabel('新しいパスワード', { exact: true }).count(), 0, 'old acceptance password/form must not be redisplayed');
    if (!authenticated) {
      assert.equal(new URL(page.url()).hash, '');
      assert.equal(server.state.inspections, 1, 'discarded invitation token must not be reused on restore');
    }
  } finally { await browser?.close(); await server.close(); }
}

test('production issued link is discarded across native BFCache history restoration', () => exercise(true));
test('production anonymous invitation token and password are discarded across native BFCache', () => exercise(false));
