import { describe, expect, it } from 'vitest';
import { checkLetterContent } from './moderation';

describe('letter content checks', () => {
  it('counts visible graphemes and accepts one to 150 characters', () => {
    expect(checkLetterContent('👨‍👩‍👧‍👦'.repeat(150))).toBeNull();
    expect(checkLetterContent('👨‍👩‍👧‍👦'.repeat(151))).toBe('length');
    expect(checkLetterContent('   ')).toBe('length');
  });

  it('flags obvious links, contact details, and profanity while allowing ordinary encouragement', () => {
    expect(checkLetterContent('오늘도 수고했어요.')).toBeNull();
    expect(checkLetterContent('http://spam.example 확인')).toBe('advertising');
    expect(checkLetterContent('연락처 010-1234-5678')).toBe('personal_info');
    expect(checkLetterContent('답장: me@example.com')).toBe('personal_info');
    expect(checkLetterContent('씨발')).toBe('profanity');
  });
});
