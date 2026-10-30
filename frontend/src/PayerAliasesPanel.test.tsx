import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import App from './App';
import type { Customer, PayerAlias, Session } from './api';
import type { components } from './generated/schema';

const customers: Customer[] = [{ id: 'customer-a', code: 'C001', name: '架空商事', version: 0 }, { id: 'customer-b', code: 'C002', name: '架空販売', version: 0 }];
const session: Session = { userId: 'actor-a', tenantId: 'tenant-a', email: 'accountant@example.test', role: 'ACCOUNTANT', csrfToken: 'csrf', csrfHeaderName: 'X-CSRF-TOKEN' };
const alias: PayerAlias = { id: 'alias-a', payerName: '架空振込人', customerId: customers[0].id, active: true, version: 0, createdByUserId: session.userId, createdAt: '2026-10-30T00:00:00Z', deactivatedByUserId: null, deactivatedAt: null, deactivationReason: null };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
type Options = { duplicate?: boolean; lostCreate?: boolean; lostDeactivation?: boolean; conflict?: boolean; paged?: boolean; rejected?: { status: number; code: string } };
function setup(options: Options = {}) {
  const stored = new Map<string, PayerAlias>([[alias.id, alias]]); let deactivations = 0;
  const fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === '/api/session') return json(session);
    if (path === '/api/customers') return json(customers);
    if (path === '/api/payer-aliases' && init?.method === 'POST') {
      if (options.rejected) return json({ code: options.rejected.code }, options.rejected.status);
      if (options.duplicate) return json({ code: 'PAYER_ALIAS_EXISTS' }, 409);
      const body: components['schemas']['PayerAliasCreateRequest'] = JSON.parse(String(init.body));
      const created = { ...alias, id: 'alias-created', payerName: body.payerName.replace(/^ +| +$/g, ''), customerId: body.customerId };
      stored.set(created.id, created); if (options.lostCreate) throw new TypeError('response lost'); return json(created, 201);
    }
    if (path.startsWith('/api/payer-aliases?')) {
      const active = [...stored.values()].filter((item) => item.active);
      if (options.paged && !path.includes('cursor=')) return json({ items: active.filter((item) => item.id === alias.id), nextCursor: 'cursor-b' });
      return json({ items: options.paged ? active.filter((item) => item.id !== alias.id).concat({ ...alias, id: 'alias-last', payerName: '後続の架空振込人', customerId: customers[1].id }) : active, nextCursor: null });
    }
    if (path.endsWith('/deactivation')) {
      deactivations++;
      const body: components['schemas']['PayerAliasDeactivateRequest'] = JSON.parse(String(init?.body));
      const id = path.split('/')[3]; const current = stored.get(id)!;
      const updated: PayerAlias = { ...current, active: false, version: current.version + 1, deactivatedByUserId: options.conflict ? 'actor-other' : session.userId, deactivatedAt: '2026-10-30T01:00:00Z', deactivationReason: options.conflict ? '別の担当者による無効化' : body.reason };
      stored.set(id, updated); if (options.conflict && deactivations === 1) return json({ code: 'VERSION_CONFLICT' }, 409);
      if (options.lostDeactivation) throw new TypeError('response lost'); return json(updated);
    }
    if (path.startsWith('/api/payer-aliases/')) return json(stored.get(path.split('/').at(-1)!) ?? alias);
    throw new Error(`Unexpected endpoint: ${path}`);
  });
  vi.stubGlobal('fetch', fetch); return fetch;
}
async function start() {
  const user = userEvent.setup(); render(<App />); await user.click(await screen.findByRole('button', { name: '振込名義の対応' })); await screen.findByRole('table', { name: '有効な名義対応一覧' }); return user;
}
async function draftRegistration(user: ReturnType<typeof userEvent.setup>, payerName = '  ｶｸｳ KAKUU kakuu é é  ') {
  await user.type(screen.getByLabelText('対応する振込名義'), payerName); await user.selectOptions(screen.getByRole('combobox', { name: '対応先の顧客' }), customers[0].id);
}
async function draftDeactivation(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: `対応 ${alias.id} の詳細` })); await user.type(await screen.findByLabelText('無効化の理由'), '  架空の登録誤り  ');
  await user.click(screen.getByRole('checkbox', { name: 'この原対応と理由を確認し、無効化する' }));
}

describe('Manual payer aliases', () => {
  it('sends payer text unchanged and shows the server response without case, width, kana or Unicode conversion', async () => {
    const fetch = setup(); const user = await start(); await draftRegistration(user); await user.click(screen.getByRole('button', { name: '名義対応を登録' }));
    expect(await screen.findByRole('status')).toHaveTextContent('対応を登録しました'); const body = fetch.mock.calls.find(([path]) => String(path) === '/api/payer-aliases')?.[1];
    expect(JSON.parse(String(body?.body))).toEqual({ payerName: '  ｶｸｳ KAKUU kakuu é é  ', customerId: customers[0].id });
    expect((body?.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe(session.csrfToken); expect(screen.getByLabelText('原対応の記録')).toHaveTextContent('ｶｸｳ KAKUU kakuu é é');
  });
  it('keeps duplicate registration input and explains that the active pair already exists', async () => {
    const fetch = setup({ duplicate: true }); const user = await start(); await draftRegistration(user); await user.click(screen.getByRole('button', { name: '名義対応を登録' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('登録済み'); expect(screen.getByLabelText('対応する振込名義')).toHaveValue('  ｶｸｳ KAKUU kakuu é é  '); expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1);
  });
  it('keeps registration uncertain until all active pages are read and never automatically resends it', async () => {
    const fetch = setup({ lostCreate: true, paged: true }); const user = await start(); await draftRegistration(user); await user.click(screen.getByRole('button', { name: '名義対応を登録' })); await screen.findByRole('alert');
    expect(screen.getByRole('button', { name: '名義対応を登録' })).toBeDisabled(); expect(screen.getByLabelText('対応する振込名義')).toHaveValue('  ｶｸｳ KAKUU kakuu é é  ');
    await user.click(screen.getByRole('button', { name: '登録結果を全ページから確認' })); const results = await screen.findByRole('table', { name: '登録結果の確認一覧' }); expect(results).toHaveTextContent('alias-created');
    expect(fetch.mock.calls.some(([path]) => String(path).endsWith('cursor=cursor-b'))).toBe(true); expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1);
    await user.click(within(results).getByRole('button', { name: '対応 alias-created の詳細' })); expect(await screen.findByLabelText('原対応の記録')).toHaveTextContent('alias-created'); expect(screen.getByRole('button', { name: '名義対応を登録' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: '結果を確認して新しい入力に切り替える' })); expect(screen.getByLabelText('対応する振込名義')).toHaveValue(''); expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1);
  });
  it('keeps the exact server cursor and replaces the current list page', async () => {
    const fetch = setup({ paged: true }); const user = await start(); await user.click(screen.getByRole('button', { name: '対応一覧の次の50件' }));
    expect(await screen.findByRole('table', { name: '有効な名義対応一覧' })).toHaveTextContent('後続の架空振込人'); expect(screen.queryByRole('button', { name: `対応 ${alias.id} の詳細` })).not.toBeInTheDocument(); expect(fetch.mock.calls.some(([path]) => String(path).endsWith('cursor=cursor-b'))).toBe(true);
  });
  it('deactivates only after reason and confirmation, retaining original identity and audit details', async () => {
    const fetch = setup(); const user = await start(); await user.click(screen.getByRole('button', { name: `対応 ${alias.id} の詳細` }));
    expect(await screen.findByRole('button', { name: '名義対応の無効化を確定' })).toBeDisabled(); await user.type(screen.getByLabelText('無効化の理由'), '架空の登録誤り'); expect(screen.getByRole('button', { name: '名義対応の無効化を確定' })).toBeDisabled();
    await user.click(screen.getByRole('checkbox', { name: 'この原対応と理由を確認し、無効化する' })); await user.click(screen.getByRole('button', { name: '名義対応の無効化を確定' })); expect(await screen.findByRole('status')).toHaveTextContent('対応を無効化しました');
    const detail = screen.getByLabelText('原対応の記録'); expect(detail).toHaveTextContent(alias.id); expect(detail).toHaveTextContent('無効化済み'); expect(detail).toHaveTextContent(session.userId); expect(detail).toHaveTextContent('架空の登録誤り'); expect(screen.queryByRole('table', { name: '有効な名義対応一覧' })).not.toBeInTheDocument();
    const mutation = fetch.mock.calls.find(([path]) => String(path).endsWith('/deactivation')); expect(JSON.parse(String(mutation?.[1]?.body))).toEqual({ version: 0, reason: '架空の登録誤り' });
  });
  it.each([{ conflict: true }, { lostDeactivation: true }])('retains the reason and requires a detail lookup after an uncertain or conflicting deactivation %j', async (options) => {
    const fetch = setup(options); const user = await start(); await draftDeactivation(user); await user.click(screen.getByRole('button', { name: '名義対応の無効化を確定' })); await screen.findByRole('alert'); expect(screen.getByRole('button', { name: '名義対応の無効化を確定' })).toBeDisabled();
    expect(screen.getByLabelText('無効化の理由')).toHaveValue('  架空の登録誤り  '); await user.click(screen.getByRole('button', { name: '無効化の最新詳細・処理結果を取得' })); expect(await screen.findByRole('status')).toHaveTextContent('再送していません'); expect(screen.getByLabelText('原対応の記録')).toHaveTextContent(options.conflict ? '別の担当者による無効化' : '架空の登録誤り'); expect(screen.getByLabelText('無効化の理由')).toHaveValue('  架空の登録誤り  '); expect(fetch.mock.calls.filter(([path]) => String(path).endsWith('/deactivation'))).toHaveLength(1);
  });
  it.each([{ status: 401, code: 'UNAUTHENTICATED' }, { status: 403, code: 'CSRF_INVALID' }, { status: 403, code: 'ACCESS_DENIED' }])('distinguishes session/CSRF expiry and permission refusal %j', async (rejected) => {
    const fetch = setup({ rejected }); const user = await start(); await draftRegistration(user); await user.click(screen.getByRole('button', { name: '名義対応を登録' })); await screen.findByRole('alert');
    if (rejected.code === 'ACCESS_DENIED') expect(screen.getByText(session.email)).toBeVisible(); else { expect(screen.getByLabelText('パスワード')).toHaveValue(''); expect(screen.queryByRole('table', { name: '有効な名義対応一覧' })).not.toBeInTheDocument(); }
    expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1);
  });
  it('clears name, customer, selected ID and deactivation reason when leaving the screen', async () => {
    setup(); const user = await start(); await draftRegistration(user); await draftDeactivation(user); await user.click(screen.getByRole('button', { name: '顧客管理' })); await user.click(screen.getByRole('button', { name: '振込名義の対応' }));
    expect(await screen.findByLabelText('対応する振込名義')).toHaveValue(''); expect(screen.getByRole('combobox', { name: '対応先の顧客' })).toHaveValue(''); expect(screen.queryByLabelText('無効化の理由')).not.toBeInTheDocument(); expect(screen.getByLabelText('対応IDで原対応・無効化履歴を開く')).toHaveValue('');
  });
});
