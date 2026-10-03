import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';

export function encodeBase32(bytes: Buffer): string {
  let bits = 0;
  let value = 0;
  let output = '';
  for (const byte of bytes) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      output += ALPHABET[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits) output += ALPHABET[(value << (5 - bits)) & 31];
  return output;
}

function decodeBase32(secret: string): Buffer {
  if (!/^[A-Z2-7]{32}$/.test(secret)) throw new Error('Invalid TOTP secret');
  let bits = 0;
  let value = 0;
  const bytes: number[] = [];
  for (const char of secret) {
    value = (value << 5) | ALPHABET.indexOf(char);
    bits += 5;
    if (bits >= 8) {
      bytes.push((value >>> (bits - 8)) & 255);
      bits -= 8;
    }
  }
  return Buffer.from(bytes);
}

export function newTotpSecret(): string {
  return encodeBase32(randomBytes(20));
}

export function hotp(secret: Buffer, counter: number, digits = 6): string {
  const input = Buffer.alloc(8);
  input.writeBigUInt64BE(BigInt(counter));
  const hash = createHmac('sha1', secret).update(input).digest();
  const offset = hash[hash.length - 1] & 15;
  const binary = (hash.readUInt32BE(offset) & 0x7fffffff) % (10 ** digits);
  return String(binary).padStart(digits, '0');
}

export function totp(secret: string, now: Date): string {
  return hotp(decodeBase32(secret), Math.floor(now.getTime() / 30_000));
}

export function verifyTotp(secret: string, code: unknown, now: Date, lastStep: number): number | null {
  if (typeof code !== 'string' || !/^\d{6}$/.test(code)) return null;
  const current = Math.floor(now.getTime() / 30_000);
  const key = decodeBase32(secret);
  for (const step of [current - 1, current, current + 1]) {
    if (step <= lastStep || step < 0) continue;
    if (timingSafeEqual(Buffer.from(hotp(key, step)), Buffer.from(code))) return step;
  }
  return null;
}
