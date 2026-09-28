const LETTERS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz';
const DIGITS = '0123456789';
const ALL = LETTERS + DIGITS;

export function randomAccountPassword(length = 8) {
  const bytes = new Uint32Array(length);
  crypto.getRandomValues(bytes);
  const chars = Array.from(bytes, value => ALL[value % ALL.length]);
  chars[0] = LETTERS[bytes[0] % LETTERS.length];
  chars[1] = DIGITS[bytes[1] % DIGITS.length];
  for (let i = chars.length - 1; i > 0; i -= 1) {
    const j = bytes[i] % (i + 1);
    const current = chars[i];
    chars[i] = chars[j];
    chars[j] = current;
  }
  return chars.join('');
}
