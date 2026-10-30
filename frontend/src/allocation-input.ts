const maxYen = 9007199254740991n;
export function parseYen(value: string): bigint | null {
  if (!/^(0|[1-9][0-9]*)$/.test(value)) return null;
  const amount = BigInt(value);
  return amount <= maxYen ? amount : null;
}
export function validReason(value: string) {
  return value.trim().length > 0 && [...value.trim()].length <= 1000 && !/[\p{Cc}\p{Zl}\p{Zp}]/u.test(value);
}
export function validVersion(value: string) { return /^(0|[1-9][0-9]{0,18})$/.test(value) && BigInt(value) <= 9223372036854775807n; }
