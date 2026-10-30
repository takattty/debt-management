import { afterEach, describe, expect, it, vi } from 'vitest';
import { formatJstTimestamp } from './timestamp-presentation';
import { invitationExpiry } from './invitation-presentation';

describe('Recorded instants in Japan time', () => {
  afterEach(() => vi.unstubAllEnvs());
  it('converts the UTC day and year boundary and explicitly labels JST', () => {
    expect(formatJstTimestamp('2026-12-31T14:59:59Z')).toBe('2026/12/31 23:59:59（日本時間 / JST）');
    expect(formatJstTimestamp('2026-12-31T15:00:00Z')).toBe('2027/01/01 00:00:00（日本時間 / JST）');
    expect(invitationExpiry('2026-10-30T15:30:00Z')).toBe('2026/10/31 00:30:00（日本時間 / JST）');
  });
  it.each(['UTC', 'America/Los_Angeles', 'Pacific/Kiritimati'])('ignores the viewer timezone %s', (timeZone) => {
    vi.stubEnv('TZ', timeZone);
    expect(formatJstTimestamp('2026-10-30T15:30:00Z')).toBe('2026/10/31 00:30:00（日本時間 / JST）');
  });
});
