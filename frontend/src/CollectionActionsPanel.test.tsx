import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import CollectionActionsPanel from './CollectionActionsPanel';
import { api, ApiError, type CollectionCase, type CollectionDraft, type Session } from './api';

const session: Session = { userId: 'actor', tenantId: 'tenant', email: '担当者@example.test', role: 'ACCOUNTANT', csrfToken: 'csrf', csrfHeaderName: 'X-CSRF-TOKEN' };
const item: CollectionCase = { invoiceId: 'invoice', customerId: 'customer', customerCode: 'C', customerName: '架空商事', invoiceNumber: 'INV', issueDate: '2026-01-01', dueDate: '2026-01-31', invoiceStatus: 'ACTIVE', invoiceVersion: 0, amount: 10000, cashAllocated: 0, explicitFees: 0, outstandingAmount: 10000, asOf: '2026-02-01', daysOverdue: 1, eligible: true, held: false, holdReason: null, latestNote: null, nextActionDate: '2026-02-05', version: 0 };
const event = { version: 1, kind: 'NOTE' as const, note: '連絡した', nextActionDate: null, reason: null, actorUserId: session.userId, actorLabel: session.email, occurredAt: '2026-01-31T15:00:00Z', held: false, holdReason: null };
const draft: CollectionDraft = { invoiceId: 'invoice', asOf: item.asOf, customerName: item.customerName, outstandingAmount: 10000, subject: '件名', body: '架空商事 御中\n期日2026-01-31、未回収10,000円', held: false, holdReason: null };
const props = () => ({ item, session, busy: false, onBusyChange: vi.fn(), onSessionExpired: vi.fn(), onRefresh: vi.fn().mockResolvedValue(undefined) });

describe('collection actions', () => {
  it('retains the current plan and sends an explicit null when the user clears it', async () => {
    const save = vi.spyOn(api, 'recordCollectionAction').mockResolvedValue({ invoiceId: item.invoiceId, version: 1, held: false, holdReason: null, latestNote: event.note, nextActionDate: null, event });
    const user = userEvent.setup(); render(<CollectionActionsPanel {...props()} />); expect(screen.getByLabelText('次回対応日（空欄で予定を解除）')).toHaveValue('2026-02-05');
    await user.type(screen.getByLabelText('対応メモ（共有）'), '連絡した'); await user.clear(screen.getByLabelText('次回対応日（空欄で予定を解除）')); await user.click(screen.getByRole('button', { name: '対応メモを記録' }));
    expect(save).toHaveBeenCalledWith(session, item.invoiceId, { version: 0, note: '連絡した', nextActionDate: null });
  });
  it('blocks blind retry, preserves drafts and recovers a committed response from GET history', async () => {
    const save = vi.spyOn(api, 'recordCollectionAction').mockRejectedValue(new ApiError(0, '応答不明'));
    vi.spyOn(api, 'listCollectionHistory').mockResolvedValue({ items: [{ ...event, nextActionDate: item.nextActionDate }], nextCursor: null });
    const user = userEvent.setup(); render(<CollectionActionsPanel {...props()} />); await user.type(screen.getByLabelText('対応メモ（共有）'), '連絡した'); await user.click(screen.getByRole('button', { name: '対応メモを記録' }));
    expect(screen.getByRole('button', { name: '対応メモを記録' })).toBeDisabled(); expect(screen.getByLabelText('対応メモ（共有）')).toHaveValue('連絡した');
    expect(screen.getByLabelText('対応メモ（共有）')).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'GETで結果・最新状況を確認' })); expect(await screen.findByText(/送信内容と一致する記録/)).toBeVisible(); expect(save).toHaveBeenCalledTimes(1); expect(screen.getByLabelText('対応メモ（共有）')).toHaveValue('');
  });
  it('requires explicit comparison after a conflict and never retries from GET', async () => {
    const save = vi.spyOn(api, 'holdCollection').mockRejectedValue(new ApiError(409, '版競合'));
    vi.spyOn(api, 'listCollectionHistory').mockResolvedValue({ items: [event], nextCursor: null });
    const user = userEvent.setup(); render(<CollectionActionsPanel {...props()} />); await user.type(screen.getByLabelText('保留・解除の理由'), '確認待ち'); await user.click(screen.getByRole('button', { name: '理由を記録して保留' }));
    await user.click(screen.getByRole('button', { name: 'GETで結果・最新状況を確認' })); expect(screen.getByRole('button', { name: '最新の版で再確認に戻る' })).toBeDisabled(); expect(screen.getByLabelText('保留・解除の理由')).toHaveValue('確認待ち');
    await user.click(screen.getByRole('checkbox')); await user.click(screen.getByRole('button', { name: '最新の版で再確認に戻る' })); expect(save).toHaveBeenCalledTimes(1); expect(screen.getByRole('button', { name: '理由を記録して保留' })).toBeEnabled();
  });
  it('refreshes a changed draft before copying and only copies a subsequent explicitly read preview', async () => {
    const user = userEvent.setup(); const clipboard = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue();
    vi.spyOn(api, 'previewCollectionDraft').mockResolvedValueOnce(draft).mockResolvedValue({ ...draft, outstandingAmount: 7000, body: '現在7,000円' });
    render(<CollectionActionsPanel {...props()} />); await user.click(screen.getByRole('button', { name: '現在の残高で文面を作成' })); await user.click(screen.getByRole('button', { name: '表示した文面をコピー' })); expect(clipboard).not.toHaveBeenCalled(); expect(screen.getByText(/更新した内容を読んで/)).toBeVisible();
    await user.click(screen.getByRole('button', { name: '表示した文面をコピー' })); expect(clipboard).toHaveBeenCalledWith('件名\n\n現在7,000円');
  });
  it('discards a formerly valid draft when the invoice becomes ineligible', async () => {
    vi.spyOn(api, 'previewCollectionDraft').mockResolvedValueOnce(draft).mockRejectedValue(new ApiError(409, '対象外'));
    const user = userEvent.setup(); render(<CollectionActionsPanel {...props()} />); await user.click(screen.getByRole('button', { name: '現在の残高で文面を作成' })); await user.click(screen.getByRole('button', { name: '表示した文面をコピー' })); expect(screen.queryByRole('button', { name: '表示した文面をコピー' })).not.toBeInTheDocument(); expect(screen.getByRole('alert')).toHaveTextContent('対象外');
  });
  it('keeps a readable preview and explains a refused clipboard permission in Japanese', async () => {
    const user = userEvent.setup(); vi.spyOn(navigator.clipboard, 'writeText').mockRejectedValue(new Error('NotAllowedError')); vi.spyOn(api, 'previewCollectionDraft').mockResolvedValue(draft);
    render(<CollectionActionsPanel {...props()} />); await user.click(screen.getByRole('button', { name: '現在の残高で文面を作成' })); await user.click(screen.getByRole('button', { name: '表示した文面をコピー' }));
    expect(screen.getByRole('alert')).toHaveTextContent('表示した文面を選択して手動でコピー'); expect(screen.getByText(/期日2026-01-31、未回収10,000円/)).toBeVisible();
  });
});
