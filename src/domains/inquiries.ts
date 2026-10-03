import { createHash, randomUUID } from 'node:crypto';
import type { Pool, PoolClient } from 'pg';

export class InquiryError extends Error {
  constructor(public readonly status: number, public readonly code: string, message: string) {
    super(message);
  }
}

const TYPES = new Set(['privacy', 'service', 'suggestion', 'removal']);
const digest = (value: string) => createHash('sha256').update(value).digest('hex');

export class PostgresInquiryService {
  constructor(private readonly db: Pool | PoolClient) {}

  async submit(input: {
    type: unknown; body: unknown; relatedLetterId: unknown; replyEmail: unknown;
    requesterUserId: string | null; source: string;
  }) {
    if (typeof input.type !== 'string' || !TYPES.has(input.type)) {
      throw new InquiryError(400, 'INVALID_INQUIRY_TYPE', '문의 종류를 다시 선택해 주세요.');
    }
    if (input.type === 'removal' && !input.requesterUserId) {
      throw new InquiryError(401, 'LOGIN_REQUIRED', '로그인이 필요합니다.');
    }
    if (typeof input.body !== 'string' || input.body.trim().length < 1 || input.body.length > 2000) {
      throw new InquiryError(400, 'INVALID_INQUIRY_BODY', '문의 내용을 1~2000자로 입력해 주세요.');
    }
    const email = input.replyEmail === undefined || input.replyEmail === null || input.replyEmail === ''
      ? null : input.replyEmail;
    if (email !== null && (typeof email !== 'string' || email.length > 254
      || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/u.test(email))) {
      throw new InquiryError(400, 'INVALID_EMAIL', '답변받을 이메일을 확인해 주세요.');
    }
    const letterId = input.relatedLetterId === undefined || input.relatedLetterId === null || input.relatedLetterId === ''
      ? null : input.relatedLetterId;
    if (letterId !== null && (typeof letterId !== 'string' || !/^[0-9a-f-]{36}$/i.test(letterId))) {
      throw new InquiryError(400, 'INVALID_LETTER', '관련 편지 정보를 확인해 주세요.');
    }
    const key = digest(`inquiry-source:${input.source}`);
    const attempts = await this.db.query<{ attempts: number }>(
      `INSERT INTO auth_attempts (key_hash, attempts, window_started_at)
       VALUES ($1, 1, now())
       ON CONFLICT (key_hash) DO UPDATE SET
         attempts = CASE WHEN auth_attempts.window_started_at < now() - interval '15 minutes'
           THEN 1 ELSE auth_attempts.attempts + 1 END,
         window_started_at = CASE WHEN auth_attempts.window_started_at < now() - interval '15 minutes'
           THEN now() ELSE auth_attempts.window_started_at END
       RETURNING attempts`, [key],
    );
    if (attempts.rows[0].attempts > 5) {
      throw new InquiryError(429, 'RATE_LIMITED', '잠시 후 다시 시도해 주세요.');
    }
    const id = randomUUID();
    try {
      await this.db.query(
        `INSERT INTO inquiries
          (id, type, body, related_letter_id, requester_user_id, reply_email, status)
         VALUES ($1, $2, $3, $4, $5, $6, 'open')`,
        [id, input.type, input.body.trim(), letterId, input.requesterUserId, email],
      );
    } catch (error) {
      if (typeof error === 'object' && error !== null && 'code' in error && error.code === '23503') {
        throw new InquiryError(400, 'INVALID_LETTER', '관련 편지를 찾을 수 없습니다.');
      }
      throw error;
    }
    return { id, status: 'open' as const };
  }
}
