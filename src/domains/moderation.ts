export type ModerationCategory = 'length' | 'profanity' | 'advertising' | 'personal_info';

const segmenter = new Intl.Segmenter('ko', { granularity: 'grapheme' });

export function checkLetterContent(input: string): ModerationCategory | null {
  const normalized = input.normalize('NFKC').replace(/[\u200B\uFEFF]/gu, '');
  const visible = normalized.trim();
  const length = [...segmenter.segment(visible)].length;
  if (length < 1 || length > 150) return 'length';

  const searchable = normalized.replace(/[\u200C\u200D]/gu, '');
  if (/(씨발|시발|좆|개새끼|병신|ㅅㅂ)/iu.test(searchable)) return 'profanity';
  if (/\b[\w.+-]+@[\w.-]+\.[a-z]{2,}\b/iu.test(normalized)
      || /\b01[016789][-\s]?\d{3,4}[-\s]?\d{4}\b/u.test(normalized)) return 'personal_info';
  if (/https?:\/\/|www\.|\b[a-z0-9-]+\.(?:com|net|org|kr|co\.kr)\b/iu.test(normalized)
      || /(광고|홍보|판매|구매|특가|할인)\s*(문의|링크|코드|이벤트)/u.test(normalized)) return 'advertising';
  return null;
}
