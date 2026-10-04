import { randomUUID } from 'node:crypto';
import { Pool, type PoolClient } from 'pg';

export class SurveyError extends Error {
  constructor(public readonly status: number, public readonly code: string, message: string) { super(message); }
}

interface SurveyRow {
  url: string;
  starts_at: Date;
  ends_at: Date;
  enabled: boolean;
}

function instant(value: unknown): Date {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(value)) {
    throw new SurveyError(422, 'INVALID_SURVEY_WINDOW', '설문 기간을 확인해 주세요.');
  }
  const date = new Date(value);
  if (!Number.isFinite(date.getTime()) || date.toISOString() !== value) {
    throw new SurveyError(422, 'INVALID_SURVEY_WINDOW', '설문 기간을 확인해 주세요.');
  }
  return date;
}

export class PostgresSurveyService {
  constructor(private readonly db: Pool | PoolClient) {}

  async current(now: Date) {
    const result = await this.db.query<SurveyRow>('SELECT url, starts_at, ends_at, enabled FROM survey_campaign WHERE id = 1');
    const row = result.rows[0];
    return row?.enabled && row.starts_at <= now && row.ends_at > now
      ? { active: true, url: row.url }
      : { active: false };
  }

  async settings() {
    const result = await this.db.query<SurveyRow>('SELECT url, starts_at, ends_at, enabled FROM survey_campaign WHERE id = 1');
    const row = result.rows[0];
    return row ? {
      url: row.url, startsAt: row.starts_at.toISOString(), endsAt: row.ends_at.toISOString(), enabled: row.enabled,
    } : null;
  }

  async configure(input: { url: unknown; startsAt: unknown; endsAt: unknown; enabled: unknown; reason: unknown }, actorId: string) {
    if (typeof input.url !== 'string') throw new SurveyError(422, 'INVALID_SURVEY_URL', '설문 주소를 확인해 주세요.');
    let url: URL;
    try { url = new URL(input.url); } catch { throw new SurveyError(422, 'INVALID_SURVEY_URL', '설문 주소를 확인해 주세요.'); }
    if (url.protocol !== 'https:' || url.username || url.password || input.url.length > 2048) {
      throw new SurveyError(422, 'INVALID_SURVEY_URL', 'HTTPS 설문 주소를 입력해 주세요.');
    }
    const startsAt = instant(input.startsAt);
    const endsAt = instant(input.endsAt);
    if (startsAt >= endsAt) throw new SurveyError(422, 'INVALID_SURVEY_WINDOW', '설문 종료 시각은 시작보다 늦어야 합니다.');
    if (typeof input.enabled !== 'boolean') throw new SurveyError(422, 'INVALID_SURVEY_STATUS', '설문 상태를 확인해 주세요.');
    if (typeof input.reason !== 'string' || input.reason.trim().length < 2 || input.reason.length > 500) {
      throw new SurveyError(422, 'INVALID_REASON', '변경 사유를 입력해 주세요.');
    }
    const client = this.db instanceof Pool ? await this.db.connect() : this.db;
    try {
      await client.query('BEGIN');
      await client.query(
        `INSERT INTO survey_campaign (id, url, starts_at, ends_at, enabled, updated_by)
         VALUES (1, $1, $2, $3, $4, $5)
         ON CONFLICT (id) DO UPDATE SET url = EXCLUDED.url, starts_at = EXCLUDED.starts_at,
           ends_at = EXCLUDED.ends_at, enabled = EXCLUDED.enabled, updated_by = EXCLUDED.updated_by,
           updated_at = now()`,
        [url.href, startsAt, endsAt, input.enabled, actorId],
      );
      await client.query(
        `INSERT INTO admin_actions (id, actor_user_id, action, target_id, reason)
         VALUES ($1, $2, 'survey.configure', '1', $3)`,
        [randomUUID(), actorId, input.reason.trim()],
      );
      await client.query('COMMIT');
      return { url: url.href, startsAt: startsAt.toISOString(), endsAt: endsAt.toISOString(), enabled: input.enabled };
    } catch (error) {
      await client.query('ROLLBACK');
      throw error;
    } finally {
      if (this.db instanceof Pool) client.release();
    }
  }
}
