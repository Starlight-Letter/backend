import { createHash, randomUUID } from 'node:crypto';
import { Pool, type PoolClient } from 'pg';
import { checkLetterContent, type ModerationCategory } from './moderation';

export class LetterError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    public readonly category?: ModerationCategory,
  ) { super(message); }
}

interface LetterRow {
  id: string;
  bridge_id: string;
  display_nickname: string;
  body: string;
  theme: number;
  is_seed: boolean;
  created_at: Date;
}

const visible = new Intl.Segmenter('ko', { granularity: 'grapheme' });
const hash = (value: string) => createHash('sha256').update(value).digest('hex');

function detail(row: LetterRow) {
  return {
    id: row.id,
    bridgeId: row.bridge_id,
    displayNickname: row.display_nickname,
    body: row.body,
    theme: row.theme,
    isSeed: row.is_seed,
    createdAt: row.created_at.toISOString(),
  };
}

function preview(row: LetterRow) {
  const characters = [...visible.segment(row.body)].map((part) => part.segment);
  return {
    id: row.id,
    bridgeId: row.bridge_id,
    theme: row.theme,
    isSeed: row.is_seed,
    preview: characters.slice(0, 40).join('') + (characters.length > 40 ? '…' : ''),
    createdAt: row.created_at.toISOString(),
  };
}

export class PostgresLetterService {
  constructor(private readonly db: Pool | PoolClient) {}

  private async transaction<T>(run: (client: PoolClient) => Promise<T>): Promise<T> {
    const client = this.db instanceof Pool ? await this.db.connect() : this.db;
    try {
      await client.query('BEGIN');
      const result = await run(client);
      await client.query('COMMIT');
      return result;
    } catch (error) {
      await client.query('ROLLBACK');
      throw error;
    } finally {
      if (this.db instanceof Pool) client.release();
    }
  }

  async create(input: {
    bridgeId: string;
    questionId: unknown;
    body: unknown;
    theme: unknown;
    userId: string;
    nickname: string;
    idempotencyKey: string | null;
    now: Date;
  }): Promise<{ id: string; replayed: boolean }> {
    if (typeof input.questionId !== 'string' || !/^[0-9a-f-]{36}$/i.test(input.questionId)) {
      throw new LetterError(400, 'INVALID_QUESTION', '질문을 다시 확인해 주세요.');
    }
    if (typeof input.body !== 'string') throw new LetterError(400, 'INVALID_BODY', '편지 내용을 입력해 주세요.');
    const letterBody = input.body;
    if (!Number.isInteger(input.theme) || (input.theme as number) < 1 || (input.theme as number) > 4) {
      throw new LetterError(400, 'INVALID_THEME', '유리병 테마를 다시 선택해 주세요.');
    }
    if (!input.idempotencyKey || input.idempotencyKey.length < 8 || input.idempotencyKey.length > 100) {
      throw new LetterError(400, 'IDEMPOTENCY_KEY_REQUIRED', '제출 요청 키를 확인해 주세요.');
    }
    const requestHash = hash(JSON.stringify({
      bridgeId: input.bridgeId, questionId: input.questionId, body: letterBody, theme: input.theme,
    }));
    return this.transaction(async (client) => {
      await client.query(
        `INSERT INTO request_keys (user_id, key, request_hash)
         VALUES ($1, $2, $3) ON CONFLICT DO NOTHING`,
        [input.userId, input.idempotencyKey, requestHash],
      );
      const key = await client.query<{ request_hash: string; letter_id: string | null }>(
        'SELECT request_hash, letter_id FROM request_keys WHERE user_id = $1 AND key = $2 FOR UPDATE',
        [input.userId, input.idempotencyKey],
      );
      if (key.rows[0].request_hash !== requestHash) {
        throw new LetterError(409, 'IDEMPOTENCY_CONFLICT', '같은 요청 키로 다른 편지를 제출할 수 없습니다.');
      }
      if (key.rows[0].letter_id) return { id: key.rows[0].letter_id, replayed: true };

      const category = checkLetterContent(letterBody);
      if (category) {
        throw new LetterError(422, 'CONTENT_REVISE', '편지 내용을 수정해 주세요.', category);
      }
      const rules = await client.query<{ category: ModerationCategory; phrase: string }>(
        'SELECT category, phrase FROM moderation_rules WHERE active = true',
      );
      const searchable = letterBody.normalize('NFKC').replace(/[\u200B\uFEFF\u200C\u200D]/gu, '').toLocaleLowerCase('ko-KR');
      for (const rule of rules.rows) {
        if (searchable.includes(rule.phrase.normalize('NFKC').toLocaleLowerCase('ko-KR'))) {
          throw new LetterError(422, 'CONTENT_REVISE', '편지 내용을 수정해 주세요.', rule.category);
        }
      }
      const bridge = await client.query<{ id: string }>(
        'SELECT id FROM bridges WHERE id = $1 AND active = true', [input.bridgeId],
      );
      if (!bridge.rows[0]) throw new LetterError(404, 'BRIDGE_NOT_FOUND', '다리를 찾을 수 없습니다.');
      const question = await client.query<{ id: string }>(
        `SELECT id FROM questions
         WHERE bridge_id = $1 AND status = 'published' AND effective_at <= $2
         ORDER BY effective_at DESC, id DESC LIMIT 1`,
        [input.bridgeId, input.now],
      );
      if (question.rows[0]?.id !== input.questionId) {
        throw new LetterError(409, 'QUESTION_CHANGED', '질문이 바뀌었어요. 새 질문을 확인해 주세요.');
      }
      const id = randomUUID();
      await client.query(
        `INSERT INTO letters
          (id, bridge_id, question_id, author_user_id, display_nickname, body, theme, is_seed, status)
         VALUES ($1, $2, $3, $4, $5, $6, $7, false, 'published')`,
        [id, input.bridgeId, input.questionId, input.userId, input.nickname, letterBody, input.theme],
      );
      await client.query(
        'UPDATE request_keys SET letter_id = $1 WHERE user_id = $2 AND key = $3',
        [id, input.userId, input.idempotencyKey],
      );
      return { id, replayed: false };
    });
  }

  async get(id: string): Promise<ReturnType<typeof detail> | null> {
    if (!/^[0-9a-f-]{36}$/i.test(id)) return null;
    const result = await this.db.query<LetterRow>(
      `SELECT id, bridge_id, display_nickname, body, theme, is_seed, created_at
       FROM letters WHERE id = $1 AND status = 'published'`, [id],
    );
    return result.rows[0] ? detail(result.rows[0]) : null;
  }

  async river(bridgeId: string, now: Date) {
    const bridge = await this.db.query<{ id: string }>(
      'SELECT id FROM bridges WHERE id = $1 AND active = true', [bridgeId],
    );
    if (!bridge.rows[0]) throw new LetterError(404, 'BRIDGE_NOT_FOUND', '다리를 찾을 수 없습니다.');
    const question = await this.db.query<{ id: string }>(
      `SELECT id FROM questions WHERE bridge_id = $1 AND status = 'published' AND effective_at <= $2
       ORDER BY effective_at DESC, id DESC LIMIT 1`,
      [bridgeId, now],
    );
    const members = question.rows[0]
      ? (await this.db.query<LetterRow>(
        `SELECT id, bridge_id, display_nickname, body, theme, is_seed, created_at
         FROM letters WHERE bridge_id = $1 AND question_id = $2
           AND is_seed = false AND status = 'published'
         ORDER BY random() LIMIT 5`, [bridgeId, question.rows[0].id],
      )).rows
      : [];
    const seeds = (await this.db.query<LetterRow>(
      `SELECT id, bridge_id, display_nickname, body, theme, is_seed, created_at
       FROM letters WHERE bridge_id = $1 AND is_seed = true AND status = 'published'
       ORDER BY random() LIMIT 5`, [bridgeId],
    )).rows;
    const selected = members.length >= 4 && seeds.length > 0
      ? [...members.slice(0, 4), seeds[0]]
      : [...members, ...seeds.slice(0, 5 - members.length)];
    return selected.sort(() => Math.random() - 0.5).map(preview);
  }
}
