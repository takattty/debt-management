import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import CollectionsPanel, { CollectionDetail } from './CollectionsPanel';
import type { CollectionCase, Invoice, Session } from './api';

const session: Session = { userId: 'u', tenantId: 't', email: 'accountant@example.test', role: 'ACCOUNTANT', csrfToken: 'csrf', csrfHeaderName: 'X-CSRF-TOKEN' };
const invoice: Invoice = { id: 'i', customerId: 'c', customerCode: 'C', customerName: '架空', invoiceNumber: 'INV', issueDate: '2026-01-01', dueDate: '2026-01-31', amount: 10000, outstandingAmount: 10000, status: 'ACTIVE', version: 0, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', cancelledAt: null };
const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200 });

async function setup(outer = false, holdWrite = false) {
  let current = { ...invoice };
  let collectionReads = 0;
  let releaseWrite: (() => void) | undefined;
  let interruptWrite: (() => void) | undefined;
  const facts = (): CollectionCase => ({ invoiceId: current.id, customerId: current.customerId, customerCode: current.customerCode, customerName: current.customerName, invoiceNumber: current.invoiceNumber, issueDate: current.issueDate, dueDate: current.dueDate, invoiceStatus: current.status, invoiceVersion: current.version, amount: current.amount, cashAllocated: 0, explicitFees: 0, outstandingAmount: current.outstandingAmount, asOf: '2026-02-01', daysOverdue: 1, eligible: current.status === 'ACTIVE', held: false, holdReason: null, latestNote: null, nextActionDate: null, version: 0 });
  const fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = new URL(String(input), 'http://localhost').pathname;
    if (path.endsWith('/history')) return json({ items: [], nextCursor: null });
    if (path === '/api/collections') return json({ items: [facts()], nextCursor: null });
    if (path === '/api/collections/i') { collectionReads++; return json(facts()); }
    if (init?.method === 'PUT' || (init?.method === 'POST' && path.endsWith('/cancel'))) {
      if (init.method === 'PUT') {
        const body = JSON.parse(String(init.body));
        current = { ...current, amount: body.amount, outstandingAmount: body.amount, version: 1 };
      } else current = { ...current, status: 'CANCELLED', outstandingAmount: 0, version: 1, cancelledAt: '2026-02-01T00:00:00Z' };
      const response = json(current);
      if (!holdWrite) return response;
      return new Promise<Response>((resolve, reject) => {
        releaseWrite = () => resolve(response);
        interruptWrite = () => reject(new TypeError('Response interrupted'));
      });
    }
    if (path === '/api/invoices/i') return json(current);
    throw new Error(`Unexpected fixture route: ${path}`);
  });
  vi.stubGlobal('fetch', fetch);
  const onBusyChange = vi.fn();
  const props = { customers: [{ id: 'c', code: 'C', name: '架空', version: 0 }], session, onBusyChange, onSessionExpired: vi.fn() };
  const user = userEvent.setup();
  if (outer) {
    render(<CollectionsPanel {...props} />);
    await user.clear(screen.getByLabelText('基準日（日本の業務日）'));
    await user.type(screen.getByLabelText('基準日（日本の業務日）'), '2026-02-01');
    await user.click(screen.getByRole('button', { name: '候補を表示' }));
    await user.click(await screen.findByRole('button', { name: '請求 INV の対応・履歴' }));
  } else render(<CollectionDetail {...props} id="i" asOf="2026-02-01" onClose={vi.fn()} />);
  await user.click(await screen.findByRole('button', { name: '原請求・訂正履歴を確認' }));
  await screen.findByRole('heading', { name: '請求の訂正・履歴：INV' });
  return { user, fetch, onBusyChange, collectionReads: () => collectionReads,
    release: () => { if (!releaseWrite) throw new Error('No pending write'); releaseWrite(); },
    interrupt: () => { if (!interruptWrite) throw new Error('No pending write'); interruptWrite(); } };
}

async function changeInvoice(user: ReturnType<typeof userEvent.setup>, cancel: boolean) {
  if (cancel) {
    await user.type(screen.getByLabelText('取消理由'), '架空取消');
    await user.click(screen.getByRole('checkbox', { name: /この請求の最新内容と取消の影響/ }));
    await user.click(screen.getByRole('button', { name: '請求の取消を確定' }));
  } else {
    await user.clear(screen.getByLabelText('訂正後の請求額（円）'));
    await user.type(screen.getByLabelText('訂正後の請求額（円）'), '12000');
    await user.type(screen.getByLabelText('訂正理由'), '架空訂正');
    await user.click(screen.getByRole('button', { name: '訂正を保存' }));
  }
}

it.each([['correction', false], ['cancellation', true]] as const)('refreshes parent facts after the actual nested InvoicePanel %s', async (_, cancel) => {
  const { user, collectionReads } = await setup();
  await changeInvoice(user, cancel);
  await waitFor(() => expect(screen.queryByRole('heading', { name: '請求の訂正・履歴：INV' })).not.toBeInTheDocument());
  const parent = screen.getByText(/状態：.*現在の未回収額/);
  expect(parent).toHaveTextContent(cancel ? '取消済み・対象外' : '対応対象');
  expect(parent).toHaveTextContent(cancel ? '￥0' : '￥12,000');
  expect(collectionReads()).toBe(2);
});

it.each([['correction', false], ['cancellation', true]] as const)('blocks parent close and selection during nested %s, then retains recovery or completion', async (_, cancel) => {
  const { user, onBusyChange, release, interrupt } = await setup(true, true);
  await changeInvoice(user, cancel);
  expect(screen.getByRole('button', { name: cancel ? '請求の取消を確定' : '訂正を保存' })).toBeDisabled();
  expect(screen.getByRole('button', { name: '対応を閉じる' })).toBeDisabled();
  expect(screen.getByLabelText('基準日（日本の業務日）')).toBeDisabled();
  expect(screen.getByRole('button', { name: '候補を表示' })).toBeDisabled();
  expect(onBusyChange).toHaveBeenLastCalledWith(true);
  if (cancel) release();
  else {
    interrupt();
    expect(await screen.findByText('処理結果・最新内容を取得して確認してください。入力中の内容は保持しています。変更は自動再送しません。')).toBeVisible();
    await user.click(screen.getByRole('button', { name: '最新内容・処理結果を取得' }));
  }
  await waitFor(() => expect(screen.queryByRole('heading', { name: '請求の訂正・履歴：INV' })).not.toBeInTheDocument());
  expect(screen.getByText(/状態：.*現在の未回収額/)).toHaveTextContent(cancel ? '￥0' : '￥12,000');
  expect(onBusyChange).toHaveBeenLastCalledWith(false);
  await user.click(screen.getByRole('button', { name: '対応を閉じる' }));
  expect(screen.queryByRole('region', { name: '請求の対応・履歴' })).not.toBeInTheDocument();
});
