import { isBlankText } from './text-input';

const maxYen = 9007199254740991n;
export function parseYen(value: string): bigint | null {
  if (!/^(0|[1-9][0-9]*)$/.test(value)) return null;
  const amount = BigInt(value);
  return amount <= maxYen ? amount : null;
}
export function validReason(value: string) {
  const chars = [...value]; let start = 0; let end = chars.length;
  while (start < end && isBlankText(chars[start])) start++;
  while (end > start && isBlankText(chars[end - 1])) end--;
  return end > start && end - start <= 1000 && !/[\p{Cc}\p{Zl}\p{Zp}]/u.test(value);
}
export function validVersion(value: string) { return typeof value === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(value) && BigInt(value) <= 9223372036854775807n; }
