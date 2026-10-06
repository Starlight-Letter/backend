import { randomUUID } from 'node:crypto';
import { Pool, type PoolClient } from 'pg';
import { checkLetterContent } from './moderation';

export class AdminError extends Error {
  constructor(public readonly status: number, public readonly code: string, message: string) { super(message); }
}

function questionBody(value: unknown): string {
  if (typeof value !== 'string') throw new AdminError(422, 'INVALID_QUESTION', '질문을 입력해 주세요.');
  const body = value.trim();
  const length = Array.from(new Intl.Segmenter('ko', { granularity: 'grapheme' }).segment(body)).length;
  if (length < 2 || length > 150) throw new AdminError(422, 'INVALID_QUESTION', '질문은 2~150자로 입력해 주세요.');
  return body;
}

function effectiveAt(value: unknown, now: Date): Date {
  if (value == null) return now;
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(value)) {
    throw new AdminError(422, 'INVALID_SCHEDULE', '예약 시각을 확인해 주세요.');
  }
  const date = new Date(value);
  const kst = new Date(date.getTime() + 9 * 60 * 60 * 1000);
  if (!Number.isFinite(date.getTime()) || date <= now || kst.getUTCDay() !== 1 ||
      kst.getUTCHours() !== 0 || kst.getUTCMinutes() !== 0 || kst.getUTCSeconds() !== 0 || kst.getUTCMilliseconds() !== 0) {
    throw new AdminError(422, 'INVALID_SCHEDULE', '한국 시간 월요일 0시 이후로 예약해 주세요.');
  }
  return date;
}

export class PostgresAdminService {
  constructor(private readonly db: Pool | PoolClient) {}

  async listQuestions(now: Date) {
    const kst = new Date(now.getTime() + 9 * 60 * 60 * 1000);
    const daysFromMonday = (kst.getUTCDay() + 6) % 7;
    const nextMonday = new Date(Date.UTC(kst.getUTCFullYear(), kst.getUTCMonth(), kst.getUTCDate() - daysFromMonday + 7)
      - 9 * 60 * 60 * 1000);
    const followingMonday = new Date(nextMonday.getTime() + 7 * 24 * 60 * 60 * 1000);
    const bridges = await this.db.query<{ id: string; name: string; display_order: number }>(
      'SELECT id, name, display_order FROM bridges WHERE active = true ORDER BY display_order',
    );
    const current = await this.db.query<{ id: string; bridge_id: string; body: string; effective_at: Date }>(
      `SELECT DISTINCT ON (bridge_id) id, bridge_id, body, effective_at FROM questions
       WHERE status = 'published' AND effective_at <= $1
       ORDER BY bridge_id, effective_at DESC, id DESC`, [now],
    );
    const scheduled = await this.db.query<{ id: string; bridge_id: string; body: string; effective_at: Date }>(
      `SELECT id, bridge_id, body, effective_at FROM questions
       WHERE status = 'published' AND effective_at > $1
       ORDER BY effective_at ASC, id ASC LIMIT 100`, [now],
    );
    return bridges.rows.map((bridge) => {
      const active = current.rows.find((row) => row.bridge_id === bridge.id);
      const future = scheduled.rows.filter((row) => row.bridge_id === bridge.id);
      return {
        bridgeId: bridge.id, bridgeName: bridge.name, displayOrder: bridge.display_order,
        currentQuestion: active ? { id: active.id, body: active.body, effectiveAt: active.effective_at.toISOString() } : null,
        scheduledQuestions: future.map((row) => ({ id: row.id, body: row.body, effectiveAt: row.effective_at.toISOString() })),
        nextWeekMissing: !future.some((row) => row.effective_at >= nextMonday && row.effective_at < followingMonday),
      };
    });
  }

  async listSeeds(bridgeId: string | null) {
    if (bridgeId) {
      const bridge = await this.db.query('SELECT id FROM bridges WHERE id = $1 AND active = true', [bridgeId]);
      if (!bridge.rows[0]) throw new AdminError(404, 'BRIDGE_NOT_FOUND', '다리를 찾을 수 없습니다.');
    }
    const result = await this.db.query<{
      id: string; bridge_id: string; body: string; display_nickname: string;
      theme: number; status: string; created_at: Date;
    }>(
      `SELECT id, bridge_id, body, display_nickname, theme, status, created_at
       FROM letters WHERE is_seed = true AND status <> 'deleted'
         AND ($1::text IS NULL OR bridge_id = $1)
       ORDER BY created_at DESC, id DESC LIMIT 200`, [bridgeId],
    );
    return result.rows.map((row) => ({
      id: row.id, bridgeId: row.bridge_id, body: row.body, displayNickname: row.display_nickname,
      theme: row.theme, status: row.status, createdAt: row.created_at.toISOString(),
    }));
  }

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

  async createQuestion(bridgeIdInput: unknown, bodyInput: unknown, effectiveAtInput: unknown, actorId: string, now: Date) {
    if (typeof bridgeIdInput !== 'string') throw new AdminError(422, 'INVALID_BRIDGE', '다리를 확인해 주세요.');
    const body = questionBody(bodyInput);
    const when = effectiveAt(effectiveAtInput, now);
    const id = randomUUID();
    try {
      return await this.transaction(async (client) => {
        const bridge = await client.query('SELECT id FROM bridges WHERE id = $1 AND active = true', [bridgeIdInput]);
        if (!bridge.rows[0]) throw new AdminError(404, 'BRIDGE_NOT_FOUND', '다리를 찾을 수 없습니다.');
        await client.query(
          `INSERT INTO questions (id, bridge_id, body, effective_at, status, created_by)
           VALUES ($1, $2, $3, $4, 'published', $5)`,
          [id, bridgeIdInput, body, when, actorId],
        );
        await client.query(
          `INSERT INTO admin_actions (id, actor_user_id, action, target_id) VALUES ($1, $2, 'question.create', $3)`,
          [randomUUID(), actorId, id],
        );
        return { id, bridgeId: bridgeIdInput, body, effectiveAt: when.toISOString() };
      });
    } catch (error) {
      if (typeof error === 'object' && error !== null && 'code' in error && error.code === '23505') {
        throw new AdminError(409, 'QUESTION_SCHEDULE_CONFLICT', '해당 시각에 이미 등록된 질문이 있습니다.');
      }
      throw error;
    }
  }

  async correctQuestion(id: string, bodyInput: unknown, actorId: string) {
    const body = questionBody(bodyInput);
    return this.transaction(async (client) => {
      const result = await client.query<{ id: string; bridge_id: string; effective_at: Date }>(
        `UPDATE questions SET body = $2, updated_at = now() WHERE id = $1 AND status = 'published'
         RETURNING id, bridge_id, effective_at`, [id, body],
      );
      if (!result.rows[0]) throw new AdminError(404, 'QUESTION_NOT_FOUND', '질문을 찾을 수 없습니다.');
      await client.query(
        `INSERT INTO admin_actions (id, actor_user_id, action, target_id) VALUES ($1, $2, 'question.correct', $3)`,
        [randomUUID(), actorId, id],
      );
      return { id, bridgeId: result.rows[0].bridge_id, body, effectiveAt: result.rows[0].effective_at.toISOString() };
    });
  }

  async createSeed(bridgeId: unknown, bodyInput: unknown, theme: unknown, displayNickname: unknown, actorId: string) {
    if (typeof bridgeId !== 'string') throw new AdminError(422, 'INVALID_BRIDGE', '다리를 확인해 주세요.');
    if (typeof bodyInput !== 'string' || checkLetterContent(bodyInput)) {
      throw new AdminError(422, 'CONTENT_REVISE', '시작 편지 내용을 수정해 주세요.');
    }
    if (!Number.isInteger(theme) || (theme as number) < 1 || (theme as number) > 4) {
      throw new AdminError(422, 'INVALID_THEME', '유리병 테마를 확인해 주세요.');
    }
    if (typeof displayNickname !== 'string' || displayNickname.trim().length < 2 ||
        Array.from(displayNickname.trim()).length > 20) {
      throw new AdminError(422, 'INVALID_NICKNAME', '표시 이름을 확인해 주세요.');
    }
    const id = randomUUID();
    return this.transaction(async (client) => {
      const bridge = await client.query('SELECT id FROM bridges WHERE id = $1 AND active = true', [bridgeId]);
      if (!bridge.rows[0]) throw new AdminError(404, 'BRIDGE_NOT_FOUND', '다리를 찾을 수 없습니다.');
      await client.query(
        `INSERT INTO letters (id, bridge_id, display_nickname, body, theme, is_seed, status)
         VALUES ($1, $2, $3, $4, $5, true, 'published')`,
        [id, bridgeId, displayNickname.trim(), bodyInput.trim(), theme],
      );
      await client.query(
        `INSERT INTO admin_actions (id, actor_user_id, action, target_id) VALUES ($1, $2, 'seed.create', $3)`,
        [randomUUID(), actorId, id],
      );
      return { id };
    });
  }

  async editSeed(id: string, bodyInput: unknown, theme: unknown, displayNickname: unknown, actorId: string) {
    if (typeof bodyInput !== 'string' || checkLetterContent(bodyInput)) {
      throw new AdminError(422, 'CONTENT_REVISE', '시작 편지 내용을 수정해 주세요.');
    }
    if (!Number.isInteger(theme) || (theme as number) < 1 || (theme as number) > 4) {
      throw new AdminError(422, 'INVALID_THEME', '유리병 테마를 확인해 주세요.');
    }
    if (typeof displayNickname !== 'string' || displayNickname.trim().length < 2 ||
        Array.from(displayNickname.trim()).length > 20) {
      throw new AdminError(422, 'INVALID_NICKNAME', '표시 이름을 확인해 주세요.');
    }
    return this.transaction(async (client) => {
      const result = await client.query<{ id: string }>(
        `UPDATE letters SET body = $2, theme = $3, display_nickname = $4
         WHERE id = $1 AND is_seed = true AND status <> 'deleted' RETURNING id`,
        [id, bodyInput.trim(), theme, displayNickname.trim()],
      );
      if (!result.rows[0]) throw new AdminError(404, 'SEED_NOT_FOUND', '시작 편지를 찾을 수 없습니다.');
      await client.query(
        `INSERT INTO admin_actions (id, actor_user_id, action, target_id) VALUES ($1, $2, 'seed.edit', $3)`,
        [randomUUID(), actorId, id],
      );
      return { id };
    });
  }

  async listModerationRules() {
    const result = await this.db.query<{ id: string; category: string; phrase: string; active: boolean }>(
      'SELECT id, category, phrase, active FROM moderation_rules ORDER BY created_at DESC, id DESC LIMIT 200',
    );
    return result.rows;
  }

  async addModerationRule(category: unknown, phraseInput: unknown, reasonInput: unknown, actorId: string) {
    if (!['profanity', 'advertising', 'personal_info'].includes(String(category))) {
      throw new AdminError(422, 'INVALID_CATEGORY', '검사 범주를 확인해 주세요.');
    }
    if (typeof phraseInput !== 'string' || phraseInput.trim().length < 2 ||
        Array.from(phraseInput.trim()).length > 30 || /[\x00-\x1f\x7f]/u.test(phraseInput)) {
      throw new AdminError(422, 'INVALID_PHRASE', '검사 문구를 2~30자로 입력해 주세요.');
    }
    if (typeof reasonInput !== 'string' || reasonInput.trim().length < 2 || reasonInput.length > 500) {
      throw new AdminError(422, 'INVALID_REASON', '변경 사유를 입력해 주세요.');
    }
    const id = randomUUID();
    return this.transaction(async (client) => {
      await client.query(
        `INSERT INTO moderation_rules (id, category, phrase, created_by) VALUES ($1, $2, $3, $4)`,
        [id, category, phraseInput.trim().normalize('NFKC'), actorId],
      );
      await client.query(
        `INSERT INTO admin_actions (id, actor_user_id, action, target_id, reason)
         VALUES ($1, $2, 'moderation.create', $3, $4)`, [randomUUID(), actorId, id, reasonInput.trim()],
      );
      return { id };
    });
  }

  async setModerationRule(id: string, active: unknown, reasonInput: unknown, actorId: string) {
    if (typeof active !== 'boolean') throw new AdminError(422, 'INVALID_STATUS', '규칙 상태를 확인해 주세요.');
    if (typeof reasonInput !== 'string' || reasonInput.trim().length < 2 || reasonInput.length > 500) {
      throw new AdminError(422, 'INVALID_REASON', '변경 사유를 입력해 주세요.');
    }
    return this.transaction(async (client) => {
      const result = await client.query(
        `UPDATE moderation_rules SET active = $2, updated_at = now() WHERE id = $1 RETURNING id`, [id, active],
      );
      if (!result.rows[0]) throw new AdminError(404, 'RULE_NOT_FOUND', '검사 규칙을 찾을 수 없습니다.');
      await client.query(
        `INSERT INTO admin_actions (id, actor_user_id, action, target_id, reason)
         VALUES ($1, $2, 'moderation.update', $3, $4)`, [randomUUID(), actorId, id, reasonInput.trim()],
      );
      return { id, active };
    });
  }

  async listReports() {
    const result = await this.db.query<{
      letter_id: string; bridge_id: string; letter_status: string; body: string; display_nickname: string;
      report_count: number; new_reporter_count: number;
      reason_codes: string[]; created_at: Date;
    }>(
      `SELECT l.id AS letter_id, l.bridge_id, l.status AS letter_status, l.body, l.display_nickname,
         count(r.id)::int AS report_count,
         count(r.id) FILTER (WHERE u.created_at >= r.created_at - interval '24 hours')::int AS new_reporter_count,
         array_agg(DISTINCT r.reason_code) AS reason_codes,
         min(r.created_at) AS created_at
       FROM letters l JOIN reports r ON r.letter_id = l.id AND r.round = l.report_round AND r.status = 'pending'
       LEFT JOIN users u ON u.id = r.reporter_user_id
       WHERE l.status <> 'deleted'
       GROUP BY l.id, l.bridge_id, l.status, l.body, l.display_nickname
       ORDER BY min(r.created_at) ASC LIMIT 100`,
    );
    return result.rows.map((row) => ({
      letterId: row.letter_id, bridgeId: row.bridge_id, letterStatus: row.letter_status,
      body: row.body, displayNickname: row.display_nickname,
      reportCount: row.report_count, newReporterCount: row.new_reporter_count,
      reasonCodes: row.reason_codes, firstReportedAt: row.created_at.toISOString(),
    }));
  }

  async findLetters(queryInput: string | null, bridgeId: string | null) {
    const query = queryInput?.trim().normalize('NFC') ?? '';
    if (Array.from(query).length < 2 || Array.from(query).length > 100) {
      throw new AdminError(422, 'INVALID_SEARCH', '검색어는 2~100자로 입력해 주세요.');
    }
    const result = await this.db.query<{
      id: string; bridge_id: string; body: string | null; display_nickname: string | null; status: string;
      theme: number; is_seed: boolean; author_user_id: string | null; question_id: string | null; created_at: Date;
    }>(
      `SELECT id, bridge_id, body, display_nickname, status, theme, is_seed, author_user_id, question_id, created_at
       FROM letters
       WHERE ($2::text IS NULL OR bridge_id = $2)
         AND (id::text = $1 OR body ILIKE '%' || $1 || '%' OR display_nickname ILIKE '%' || $1 || '%')
       ORDER BY created_at DESC, id DESC
       LIMIT 50`, [query, bridgeId],
    );
    return result.rows.map((row) => ({
      id: row.id, bridgeId: row.bridge_id, body: row.body, displayNickname: row.display_nickname,
      status: row.status, theme: row.theme, isSeed: row.is_seed, authorUserId: row.author_user_id,
      questionId: row.question_id, createdAt: row.created_at.toISOString(),
    }));
  }

  async alerts() {
    const inquiries = await this.db.query<{ count: number }>(
        `SELECT count(*)::int AS count FROM inquiries
         WHERE type IN ('privacy', 'removal') AND status <> 'resolved'`,
    );
    const reports = await this.db.query<{ total: number; hidden: number }>(
        `SELECT count(*)::int AS total,
           count(*) FILTER (WHERE status = 'hidden')::int AS hidden
         FROM letters l WHERE status <> 'deleted' AND EXISTS (
           SELECT 1 FROM reports r WHERE r.letter_id = l.id
             AND r.round = l.report_round AND r.status = 'pending'
         )`,
    );
    return {
      privacyInquiries: inquiries.rows[0].count,
      pendingReportLetters: reports.rows[0].total,
      hiddenPendingReview: reports.rows[0].hidden,
    };
  }

  async reviewLetter(id: string, decision: unknown, reasonInput: unknown, actorId: string) {
    if (!['hide', 'keep_hidden', 'restore', 'dismiss', 'delete'].includes(String(decision))) {
      throw new AdminError(422, 'INVALID_DECISION', '처리 방법을 확인해 주세요.');
    }
    if (typeof reasonInput !== 'string' || reasonInput.trim().length < 2 || reasonInput.length > 500) {
      throw new AdminError(422, 'INVALID_REASON', '처리 사유를 입력해 주세요.');
    }
    return this.transaction(async (client) => {
      const letter = await client.query<{ status: string; report_round: number }>(
        'SELECT status, report_round FROM letters WHERE id = $1 FOR UPDATE', [id],
      );
      if (!letter.rows[0] || letter.rows[0].status === 'deleted') {
        throw new AdminError(404, 'LETTER_NOT_FOUND', '편지를 찾을 수 없습니다.');
      }
      const status = letter.rows[0].status;
      if (decision === 'hide' && status !== 'published') throw new AdminError(409, 'INVALID_STATE', '현재 상태에서 숨길 수 없습니다.');
      if (decision === 'keep_hidden' && status !== 'hidden') throw new AdminError(409, 'INVALID_STATE', '숨긴 편지만 계속 숨길 수 있습니다.');
      if (decision === 'restore' && status !== 'hidden') throw new AdminError(409, 'INVALID_STATE', '숨긴 편지만 복원할 수 있습니다.');
      if (decision === 'dismiss' && status !== 'published') throw new AdminError(409, 'INVALID_STATE', '공개 중인 편지만 신고를 기각할 수 있습니다.');
      if (decision === 'delete') {
        const reactions = await client.query('DELETE FROM reactions WHERE letter_id = $1', [id]);
        const saves = await client.query('DELETE FROM saves WHERE letter_id = $1', [id]);
        await client.query(`UPDATE letters SET status = 'deleted', body = NULL, display_nickname = NULL,
          author_user_id = NULL, deleted_at = now() WHERE id = $1`, [id]);
        await client.query(`UPDATE reports SET status = 'upheld' WHERE letter_id = $1 AND status = 'pending'`, [id]);
        await client.query(
          `INSERT INTO deletion_events (id, letter_id, actor_user_id, reason, reaction_count, save_count)
           VALUES ($1, $2, $3, $4, $5, $6)`,
          [randomUUID(), id, actorId, reasonInput.trim(), reactions.rowCount ?? 0, saves.rowCount ?? 0],
        );
      } else if (decision === 'hide') {
        await client.query(`UPDATE letters SET status = 'hidden', hidden_at = now() WHERE id = $1`, [id]);
      } else if (decision === 'keep_hidden') {
        await client.query(`UPDATE reports SET status = 'upheld'
          WHERE letter_id = $1 AND round = $2 AND status = 'pending'`, [id, letter.rows[0].report_round]);
      } else {
        await client.query(`UPDATE reports SET status = 'dismissed'
          WHERE letter_id = $1 AND round = $2 AND status = 'pending'`, [id, letter.rows[0].report_round]);
        await client.query(`UPDATE letters SET status = 'published', hidden_at = NULL, report_round = report_round + 1 WHERE id = $1`, [id]);
      }
      await client.query(
        `INSERT INTO admin_actions (id, actor_user_id, action, target_id, reason)
         VALUES ($1, $2, $3, $4, $5)`, [randomUUID(), actorId, `letter.${decision}`, id, reasonInput.trim()],
      );
      return { id, status: decision === 'delete' ? 'deleted' :
        decision === 'hide' || decision === 'keep_hidden' ? 'hidden' : 'published' };
    });
  }

  async listInquiries() {
    const result = await this.db.query<{
      id: string; type: string; body: string; related_letter_id: string | null;
      reply_email: string | null; status: string; created_at: Date;
      requester_user_id: string | null; related_letter_author_user_id: string | null;
    }>(
      `SELECT i.id, i.type, i.body, i.related_letter_id, i.requester_user_id,
         l.author_user_id AS related_letter_author_user_id, i.reply_email, i.status, i.created_at
       FROM inquiries i LEFT JOIN letters l ON l.id = i.related_letter_id
       WHERE i.status <> 'resolved'
       ORDER BY CASE WHEN i.type IN ('privacy', 'removal') THEN 0 ELSE 1 END, i.created_at ASC LIMIT 100`,
    );
    return result.rows.map((row) => ({
      id: row.id, type: row.type, body: row.body, relatedLetterId: row.related_letter_id,
      requesterUserId: row.requester_user_id, relatedLetterAuthorUserId: row.related_letter_author_user_id,
      replyEmail: row.reply_email, status: row.status, createdAt: row.created_at.toISOString(),
    }));
  }

  async updateInquiry(id: string, status: unknown, reasonInput: unknown, actorId: string) {
    if (status !== 'in_progress' && status !== 'resolved') {
      throw new AdminError(422, 'INVALID_STATUS', '문의 상태를 확인해 주세요.');
    }
    if (typeof reasonInput !== 'string' || reasonInput.trim().length < 2 || reasonInput.length > 500) {
      throw new AdminError(422, 'INVALID_REASON', '처리 사유를 입력해 주세요.');
    }
    return this.transaction(async (client) => {
      const result = await client.query<{ id: string }>(
        `UPDATE inquiries SET status = $2,
           resolved_at = CASE WHEN $2 = 'resolved' THEN now() ELSE NULL END
         WHERE id = $1 AND status <> 'resolved' RETURNING id`, [id, status],
      );
      if (!result.rows[0]) throw new AdminError(404, 'INQUIRY_NOT_FOUND', '문의를 찾을 수 없습니다.');
      await client.query(
        `INSERT INTO admin_actions (id, actor_user_id, action, target_id, reason)
         VALUES ($1, $2, 'inquiry.update', $3, $4)`, [randomUUID(), actorId, id, reasonInput.trim()],
      );
      return { id, status };
    });
  }
}
