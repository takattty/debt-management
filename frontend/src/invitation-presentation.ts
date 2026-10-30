import type { InvitationRole } from './api';
import { formatJstTimestamp } from './timestamp-presentation';

export const invitationRoleNames: Record<InvitationRole, string> = { ADMIN: '管理者', ACCOUNTANT: '経理担当者' };
export const invalidInvitationMessage = 'この招待は利用できません。有効な招待リンクを管理者に確認してください。';

export function invitationExpiry(value: string) {
  return formatJstTimestamp(value);
}

export function shareableInvitationUrl(relative: string) {
  const url = new URL(relative, window.location.origin);
  if (url.origin !== window.location.origin || url.pathname !== '/accept-invitation' || url.search || url.username || url.password
    || !new URLSearchParams(url.hash.slice(1)).get('token')) throw new Error('招待リンクを表示できません。管理者にご確認ください。');
  return url.href;
}
