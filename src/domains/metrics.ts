import { createHash } from 'node:crypto';
import type { Pool, PoolClient } from 'pg';

export class MetricsError extends Error {
  constructor(public readonly status: number, public readonly code: string, message: string) { super(message); }
}

export class PostgresMetricsService {
  constructor(private readonly db: Pool | PoolClient) {}

  private period(fromInput: string | null, toInput: string | null): [Date | null, Date | null] {
    if (fromInput === null && toInput === null) return [null, null];
    const valid = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/;
    if (!fromInput || !toInput || !valid.test(fromInput) || !valid.test(toInput)) {
      throw new MetricsError(400, 'INVALID_PERIOD', '조회 기간을 확인해 주세요.');
    }
    const from = new Date(fromInput);
    const to = new Date(toInput);
    if (!Number.isFinite(from.getTime()) || !Number.isFinite(to.getTime()) || from >= to) {
      throw new MetricsError(400, 'INVALID_PERIOD', '조회 기간을 확인해 주세요.');
    }
    return [from, to];
  }

  async surveyOpen(eventId: unknown, bridgeId: unknown, source: string, now: Date) {
    const active = await this.db.query(
      `SELECT id FROM survey_campaign WHERE id = 1 AND enabled = true
       AND starts_at <= $1 AND ends_at > $1`, [now],
    );
    if (!active.rows[0]) throw new MetricsError(409, 'SURVEY_CLOSED', '설문 기간이 아닙니다.');
    return this.recordEvent(eventId, 'survey_open', bridgeId, null, source);
  }

  async record(eventId: unknown, eventType: unknown, bridgeIdInput: unknown, letterId: unknown, source: string) {
    if (!['qr_entry', 'letter_open', 'five_reached', 'signup_start', 'write_start'].includes(String(eventType))) {
      throw new MetricsError(400, 'INVALID_EVENT_TYPE', '이벤트 종류를 확인해 주세요.');
    }
    return this.recordEvent(eventId, eventType, bridgeIdInput, letterId, source);
  }

  private async recordEvent(eventId: unknown, eventType: unknown, bridgeIdInput: unknown, letterId: unknown, source: string) {
    if (typeof eventId !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(eventId)) {
      throw new MetricsError(400, 'INVALID_EVENT_ID', '이벤트 식별값을 확인해 주세요.');
    }
    if (bridgeIdInput !== null && bridgeIdInput !== undefined && typeof bridgeIdInput !== 'string') {
      throw new MetricsError(400, 'INVALID_BRIDGE', '다리 정보를 확인해 주세요.');
    }
    let bridgeId = bridgeIdInput ?? null;
    let letterKind: 'seed' | 'member' | null = null;
    if (eventType === 'letter_open') {
      if (typeof letterId !== 'string' || !/^[0-9a-f-]{36}$/i.test(letterId)) {
        throw new MetricsError(400, 'INVALID_LETTER', '편지 정보를 확인해 주세요.');
      }
      const letter = await this.db.query<{ bridge_id: string; is_seed: boolean }>(
        `SELECT bridge_id, is_seed FROM letters WHERE id = $1 AND status = 'published'`, [letterId],
      );
      if (!letter.rows[0]) throw new MetricsError(404, 'LETTER_NOT_FOUND', '편지를 찾을 수 없습니다.');
      bridgeId = letter.rows[0].bridge_id;
      letterKind = letter.rows[0].is_seed ? 'seed' : 'member';
    } else if (eventType !== 'survey_open' && !bridgeId) {
      throw new MetricsError(400, 'BRIDGE_REQUIRED', '다리를 선택해 주세요.');
    }
    const existing = await this.db.query<{ bridge_id: string | null; event_type: string; letter_kind: string | null }>(
      'SELECT bridge_id, event_type, letter_kind FROM metric_events WHERE id = $1', [eventId],
    );
    if (existing.rows[0]) {
      if (existing.rows[0].bridge_id !== bridgeId || existing.rows[0].event_type !== eventType ||
          existing.rows[0].letter_kind !== letterKind) {
        throw new MetricsError(409, 'EVENT_CONFLICT', '동일한 이벤트 식별값을 사용할 수 없습니다.');
      }
      return { counted: false };
    }
    const sourceKey = createHash('sha256').update(`metric:${eventType}:${source}`).digest('hex');
    const attempts = await this.db.query<{ attempts: number }>(
      `INSERT INTO auth_attempts (key_hash, attempts, window_started_at) VALUES ($1, 1, now())
       ON CONFLICT (key_hash) DO UPDATE SET
         attempts = CASE WHEN auth_attempts.window_started_at < now() - interval '15 minutes'
           THEN 1 ELSE auth_attempts.attempts + 1 END,
         window_started_at = CASE WHEN auth_attempts.window_started_at < now() - interval '15 minutes'
           THEN now() ELSE auth_attempts.window_started_at END
       RETURNING attempts`, [sourceKey],
    );
    if (attempts.rows[0].attempts > (eventType === 'survey_open' ? 20 : 100)) {
      throw new MetricsError(429, 'RATE_LIMITED', '잠시 후 다시 시도해 주세요.');
    }
    try {
      const result = await this.db.query(
        `INSERT INTO metric_events (id, event_type, bridge_id, letter_kind)
         VALUES ($1, $2, $3, $4) ON CONFLICT (id) DO NOTHING`, [eventId, eventType, bridgeId, letterKind],
      );
      return { counted: result.rowCount === 1 };
    } catch (error) {
      if (typeof error === 'object' && error !== null && 'code' in error && error.code === '23503') {
        throw new MetricsError(404, 'BRIDGE_NOT_FOUND', '다리를 찾을 수 없습니다.');
      }
      throw error;
    }
  }

  async summary(fromInput: string | null = null, toInput: string | null = null) {
    const [from, to] = this.period(fromInput, toInput);
    const result = await this.db.query<{ bridge_id: string | null; count: number }>(
        `SELECT bridge_id, count(*)::int AS count FROM metric_events
         WHERE event_type = 'survey_open'
           AND ($1::timestamptz IS NULL OR created_at >= $1)
           AND ($2::timestamptz IS NULL OR created_at < $2)
         GROUP BY bridge_id ORDER BY bridge_id NULLS FIRST`, [from, to],
    );
    const funnel = await this.db.query<{ bridge_id: string; event_type: string; letter_kind: string | null; count: number }>(
        `SELECT bridge_id, event_type, letter_kind, count(*)::int AS count
         FROM metric_events WHERE bridge_id IS NOT NULL AND event_type <> 'survey_open'
           AND ($1::timestamptz IS NULL OR created_at >= $1)
           AND ($2::timestamptz IS NULL OR created_at < $2)
         GROUP BY bridge_id, event_type, letter_kind ORDER BY bridge_id, event_type, letter_kind`, [from, to],
    );
    const signups = await this.db.query<{ count: number }>(
      `SELECT count(*)::int AS count FROM users WHERE role = 'member'
         AND ($1::timestamptz IS NULL OR created_at >= $1)
         AND ($2::timestamptz IS NULL OR created_at < $2)`, [from, to],
    );
    const letters = await this.db.query<{ bridge_id: string; count: number }>(
        `SELECT bridge_id, count(*)::int AS count FROM letters
         WHERE is_seed = false AND status <> 'deleted'
           AND ($1::timestamptz IS NULL OR created_at >= $1)
           AND ($2::timestamptz IS NULL OR created_at < $2)
         GROUP BY bridge_id ORDER BY bridge_id`, [from, to],
    );
    const reactions = await this.db.query<{ bridge_id: string; count: number }>(
        `SELECT l.bridge_id, count(*)::int AS count FROM reactions r JOIN letters l ON l.id = r.letter_id
         WHERE r.active = true AND l.status = 'published'
           AND ($1::timestamptz IS NULL OR r.activated_at >= $1)
           AND ($2::timestamptz IS NULL OR r.activated_at < $2)
         GROUP BY l.bridge_id ORDER BY l.bridge_id`, [from, to],
    );
    return {
      surveyOpens: result.rows.map((row) => ({ bridgeId: row.bridge_id, count: row.count })),
      eventsByBridge: funnel.rows.map((row) => ({ bridgeId: row.bridge_id, eventType: row.event_type,
        letterKind: row.letter_kind, count: row.count })),
      signupCompleted: signups.rows[0].count,
      memberLettersByBridge: letters.rows.map((row) => ({ bridgeId: row.bridge_id, count: row.count })),
      activeReactionsByBridge: reactions.rows.map((row) => ({ bridgeId: row.bridge_id, count: row.count })),
    };
  }
}
