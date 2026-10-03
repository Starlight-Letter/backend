import { describe, expect, it } from 'vitest';
import { hotp } from './totp';

describe('RFC 6238 SHA-1 one-time password', () => {
  it('matches the published 59-second SHA-1 vector', () => {
    expect(hotp(Buffer.from('12345678901234567890'), 1, 8)).toBe('94287082');
  });
});
