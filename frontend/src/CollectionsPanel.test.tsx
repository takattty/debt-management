import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import CollectionsPanel, { CollectionDetail, japanBusinessDate } from './CollectionsPanel';
import { api, ApiError, type CollectionCase, type Session } from './api';

const session: Session = { userId: 'user', tenantId: 'tenant', email: 'accountant@example.test', role: 'ACCOUNTANT', csrfToken: 'token', csrfHeaderName: 'X-CSRF-TOKEN' };
const item: CollectionCase = { invoiceId: 'invoice', customerId: 'customer', customerCode: 'C001', customerName: '架空商事', invoiceNumber: 'INV-001', issueDate: '2026-01-01', dueDate: '2026-01-31', invoiceStatus: 'ACTIVE', invoiceVersion: 0, amount: 10000, cashAllocated: 2500, explicitFees: 500, outstandingAmount: 7000, asOf: '2026-02-01', daysOverdue: 1, eligible: true, held: false, holdReason: null, latestNote: null, nextActionDate: null, version: 0 };
const props = () => ({ customers: [], session, onBusyChange: vi.fn(), onSessionExpired: vi.fn() });

describe('collections read views', () => {
  it('uses the Japanese business date across UTC midnight without transforming date strings', () => {
    expect(japanBusinessDate(new Date('2026-01-31T14:59:59Z'))).toBe('2026-01-31');
    expect(japanBusinessDate(new Date('2026-01-31T15:00:00Z'))).toBe('2026-02-01');
  });
  it('keeps applied asOf/view when paging and fetches exactly one bounded page per click', async () => {
    const list = vi.spyOn(api, 'listCollections').mockResolvedValueOnce({ items: [item], nextCursor: 'next' }).mockResolvedValueOnce({ items: [{ ...item, invoiceId: 'second', invoiceNumber: 'INV-002' }], nextCursor: null });
    const user = userEvent.setup(); render(<CollectionsPanel {...props()} />);
    await user.clear(screen.getByLabelText('基準日（日本の業務日）')); await user.type(screen.getByLabelText('基準日（日本の業務日）'), '2026-02-01');
    await user.selectOptions(screen.getByLabelText('対応の状態'), 'HELD'); await user.click(screen.getByRole('button', { name: '候補を表示' }));
    expect(await screen.findByText('INV-001')).toBeVisible(); expect(screen.getByRole('table', { name: '督促候補一覧' })).toHaveTextContent('￥7,000');
    await user.selectOptions(screen.getByLabelText('対応の状態'), 'ALL'); await user.click(screen.getByRole('button', { name: '次の50件' }));
    expect(await screen.findByText('INV-002')).toBeVisible(); expect(list.mock.calls).toEqual([[{ asOf: '2026-02-01', view: 'HELD', limit: 50 }], [{ asOf: '2026-02-01', view: 'HELD', limit: 50, cursor: 'next' }]]);
  });
  it('retains cancelled detail and appends original history with recorded actor and JST', async () => {
    vi.spyOn(api, 'getCollection').mockResolvedValue({ ...item, invoiceStatus: 'CANCELLED', eligible: false, outstandingAmount: 0 });
    const event = { version: 1, kind: 'NOTE' as const, note: '原本メモ', reason: null, actorUserId: 'actor', actorLabel: '記録時の担当者@example.test', occurredAt: '2026-01-31T15:01:02Z', held: false, holdReason: null, nextActionDate: '2026-02-05' };
    vi.spyOn(api, 'listCollectionHistory').mockResolvedValueOnce({ items: [event], nextCursor: 1 }).mockResolvedValueOnce({ items: [{ ...event, version: 2, kind: 'HOLD', reason: '顧客と確認中', held: true }], nextCursor: null });
    const user = userEvent.setup(); render(<CollectionDetail {...props()} id="invoice" asOf="2026-02-01" onClose={vi.fn()} />);
    expect(await screen.findByText(/取消済み・対象外/)).toHaveTextContent('￥0'); expect(screen.getByText(/記録時の担当者/)).toHaveTextContent('2026/02/01 00:01:02（日本時間 / JST）');
    await user.click(screen.getByRole('button', { name: '次の50件の対応履歴' })); expect(await screen.findByText('第2版：保留')).toBeVisible();
    expect(within(screen.getByRole('list')).getAllByText(/原本メモ/)).toHaveLength(2);
  });
  it('clears the workspace on session expiry instead of showing tenant data', async () => {
    const callbacks = props(); vi.spyOn(api, 'listCollections').mockRejectedValue(new ApiError(401, '再ログイン'));
    const user = userEvent.setup(); render(<CollectionsPanel {...callbacks} />); await user.click(screen.getByRole('button', { name: '候補を表示' }));
    expect(callbacks.onSessionExpired).toHaveBeenCalledWith(expect.objectContaining({ status: 401 }));
  });
  it('prevents parent navigation and filter changes while a real action is pending', async () => {
    vi.spyOn(api, 'listCollections').mockResolvedValue({ items: [item], nextCursor: null }); vi.spyOn(api, 'getCollection').mockResolvedValue(item); vi.spyOn(api, 'listCollectionHistory').mockResolvedValue({ items: [], nextCursor: null });
    let finish!: (response: Awaited<ReturnType<typeof api.recordCollectionAction>>) => void;
    vi.spyOn(api, 'recordCollectionAction').mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    const callbacks = props(); const user = userEvent.setup(); render(<CollectionsPanel {...callbacks} />); await user.click(screen.getByRole('button', { name: '候補を表示' })); await user.click(await screen.findByRole('button', { name: '請求 INV-001 の対応・履歴' }));
    await user.type(await screen.findByLabelText('対応メモ（共有）'), '保存中のメモ'); await user.click(screen.getByRole('button', { name: '対応メモを記録' }));
    expect(screen.getByRole('button', { name: '候補を表示' })).toBeDisabled(); expect(screen.getByLabelText('対応の状態')).toBeDisabled(); expect(screen.getByRole('button', { name: '対応を閉じる' })).toBeDisabled(); expect(callbacks.onBusyChange).toHaveBeenLastCalledWith(true);
    finish({ invoiceId: item.invoiceId, version: 1, held: false, holdReason: null, latestNote: '保存中のメモ', nextActionDate: null, event: { version: 1, kind: 'NOTE', note: '保存中のメモ', reason: null, actorUserId: 'user', actorLabel: session.email, occurredAt: '2026-01-31T15:00:00Z', held: false, holdReason: null, nextActionDate: null } });
    expect(await screen.findByText('対応を記録しました。')).toBeVisible();
  });
});
