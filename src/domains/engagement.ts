import { randomUUID } from 'node:crypto';
import type { Pool, PoolClient } from 'pg';

export class EngagementError extends Error {
  constructor(public readonly status: number, public readonly code: string, message: string) {
    super(message);
  }
}

type Db = Pool | PoolClient;

function currentPeriodStart(now: Date, period: string): Date {
  const kst = new Date(now.getTime() + 9 * 60 * 60 * 1000);
  if (period === 'month') {
    return new Date(Date.UTC(kst.getUTCFullYear(), kst.getUTCMonth(), 1) - 9 * 60 * 60 * 1000);
  }
  if (period === 'week') {
    const daysFromMonday = (kst.getUTCDay() + 6) % 7;
    return new Date(Date.UTC(kst.getUTCFullYear(), kst.getUTCMonth(), kst.getUTCDate() - daysFromMonday)
      - 9 * 60 * 60 * 1000);
  }
  throw new EngagementError(400, 'INVALID_PERIOD', '기간을 다시 선택해 주세요.');
}

function assertLetterId(id: string): void {
  if (!/^[0-9a-f-]{36}$/i.test(id)) throw new EngagementError(404, 'LETTER_NOT_FOUND', '편지를 찾을 수 없습니다.');
}

export class PostgresEngagementService {
  constructor(private readonly db: Db) {}

  private async published(id: string): Promise<void> {
    assertLetterId(id);
    const found = await this.db.query('SELECT id FROM letters WHERE id = $1 AND status = $2', [id, 'published']);
    if (!found.rows[0]) throw new EngagementError(404, 'LETTER_NOT_FOUND', '편지를 찾을 수 없습니다.');
  }

  async react(letterId: string, userId: string, active: boolean, now: Date) {
    await this.published(letterId);
    if (active) {
      await this.db.query(
        `INSERT INTO reactions (id, letter_id, user_id, active, activated_at)
         VALUES ($1, $2, $3, true, $4)
         ON CONFLICT (user_id, letter_id) WHERE user_id IS NOT NULL
         DO UPDATE SET active = true,
           activated_at = CASE WHEN reactions.active THEN reactions.activated_at ELSE EXCLUDED.activated_at END`,
        [randomUUID(), letterId, userId, now],
      );
    } else {
      await this.db.query(
        'UPDATE reactions SET active = false WHERE letter_id = $1 AND user_id = $2',
        [letterId, userId],
      );
    }
    const count = await this.db.query<{ count: number }>(
      'SELECT count(*)::int AS count FROM reactions WHERE letter_id = $1 AND active = true', [letterId],
    );
    return { active, reactionCount: count.rows[0].count };
  }

  async save(letterId: string, userId: string, saved: boolean) {
    assertLetterId(letterId);
    if (saved) {
      await this.published(letterId);
      await this.db.query(
        `INSERT INTO saves (user_id, letter_id) VALUES ($1, $2)
         ON CONFLICT (user_id, letter_id) DO NOTHING`, [userId, letterId],
      );
    } else {
      await this.db.query('DELETE FROM saves WHERE user_id = $1 AND letter_id = $2', [userId, letterId]);
    }
    return { saved };
  }

  async saved(userId: string) {
    const result = await this.db.query<{
      id: string; bridge_id: string; status: string; body: string | null;
      display_nickname: string | null; saved_at: Date;
    }>(
      `SELECT l.id, l.bridge_id, l.status, l.body, l.display_nickname, s.created_at AS saved_at
       FROM saves s JOIN letters l ON l.id = s.letter_id
       WHERE s.user_id = $1 AND l.status != 'deleted'
       ORDER BY s.created_at DESC, l.id`, [userId],
    );
    return result.rows.map((row) => row.status === 'hidden'
      ? { id: row.id, bridgeId: row.bridge_id, unavailable: true, message: '지금은 볼 수 없는 편지', savedAt: row.saved_at.toISOString() }
      : { id: row.id, bridgeId: row.bridge_id, unavailable: false,
        preview: [...row.body!].slice(0, 40).join(''), displayNickname: row.display_nickname,
        savedAt: row.saved_at.toISOString() });
  }

  async best(scope: string | null, bridgeId: string | null, period: string | null, now: Date) {
    if (scope !== 'bridge' && scope !== 'all') {
      throw new EngagementError(400, 'INVALID_SCOPE', '범위를 다시 선택해 주세요.');
    }
    if (scope === 'bridge' && !bridgeId) {
      throw new EngagementError(400, 'BRIDGE_REQUIRED', '다리를 선택해 주세요.');
    }
    const start = currentPeriodStart(now, period ?? '');
    const result = await this.db.query<{
      id: string; bridge_id: string; body: string; display_nickname: string;
      theme: number; reaction_count: number; last_reaction_at: Date;
    }>(
      `SELECT l.id, l.bridge_id, l.body, l.display_nickname, l.theme,
              count(r.id)::int AS reaction_count, max(r.activated_at) AS last_reaction_at
       FROM reactions r JOIN letters l ON l.id = r.letter_id
       WHERE r.active = true AND r.activated_at >= $1 AND r.activated_at <= $2
         AND l.status = 'published' AND ($3::text IS NULL OR l.bridge_id = $3)
       GROUP BY l.id
       ORDER BY reaction_count DESC, last_reaction_at DESC, l.id ASC
       LIMIT 10`,
      [start, now, scope === 'bridge' ? bridgeId : null],
    );
    return result.rows.map((row, index) => ({
      rank: index + 1, id: row.id, bridgeId: row.bridge_id,
      preview: [...row.body].slice(0, 40).join(''), displayNickname: row.display_nickname,
      theme: row.theme, reactionCount: row.reaction_count,
    }));
  }
}
