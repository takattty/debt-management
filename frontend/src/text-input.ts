// Match Java String.isBlank(), used by the server. NBSP, figure space and
// narrow NBSP are significant text, while U+001C–U+001F are whitespace.
// eslint-disable-next-line no-control-regex -- Java whitespace includes these control characters.
export const isBlankText = (value: string) => /^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]*$/u.test(value);
