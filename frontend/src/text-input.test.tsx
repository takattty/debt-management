import { expect, it } from 'vitest';
import { isBlankText } from './text-input';

it('recognizes Java blank input including separators that JavaScript trim preserves', () => {
  for (const value of ['', ' \t\r\n', '\u001c\u001f', '\u1680\u2000\u2028\u205f\u3000']) expect(isBlankText(value)).toBe(true);
});

it('keeps nonbreaking spaces and text significant as Java does', () => {
  for (const value of ['\u00a0', '\u2007', '\u202f', ' 𠮷 ', '\ufeff']) expect(isBlankText(value)).toBe(false);
});
