import { randomUUID } from 'node:crypto';
import { Pool, type PoolClient } from 'pg';

export class ReportError extends Error {
  constructor(public readonly status: number, public readonly code: string, message: string) {
    super(message);
  }
}

const REASONS = new Set(['inappropriate', 'advertising', 'personal_info', 'other']);

export class PostgresReportService {
  constructor(private readonly db: Pool | PoolClient) {}

  async report(letterId: string, userId: string, reasonCode: unknown) {
    if (typeof reasonCode !== 'string' || !REASONS.has(reasonCode)) {
      throw new ReportError(400, 'INVALID_REASON', '신고 사유를 다시 선택해 주세요.');
    }
    if (!/^[0-9a-f-]{36}$/i.test(letterId)) {
      throw new ReportError(404, 'LETTER_NOT_FOUND', '편지를 찾을 수 없습니다.');
    }
    const client = this.db instanceof Pool ? await this.db.connect() : this.db;
    try {
      await client.query('BEGIN');
      const letter = await client.query<{ status: string; report_round: number }>(
        'SELECT status, report_round FROM letters WHERE id = $1 FOR UPDATE', [letterId],
      );
      if (!letter.rows[0] || letter.rows[0].status !== 'published') {
        throw new ReportError(404, 'LETTER_NOT_FOUND', '편지를 찾을 수 없습니다.');
      }
      const duplicate = await client.query(
        'SELECT id FROM reports WHERE letter_id = $1 AND reporter_user_id = $2', [letterId, userId],
      );
      if (duplicate.rows[0]) throw new ReportError(409, 'ALREADY_REPORTED', '이미 신고한 편지입니다.');

      const round = letter.rows[0].report_round;
      await client.query(
        `INSERT INTO reports (id, letter_id, reporter_user_id, round, reason_code, status)
         VALUES ($1, $2, $3, $4, $5, 'pending')`,
        [randomUUID(), letterId, userId, round, reasonCode],
      );
      const total = await client.query<{ count: number }>(
        `SELECT count(*)::int AS count FROM reports
         WHERE letter_id = $1 AND round = $2 AND status = 'pending'`, [letterId, round],
      );
      const hidden = total.rows[0].count >= 5;
      if (hidden) {
        await client.query(
          `UPDATE letters SET status = 'hidden', hidden_at = now() WHERE id = $1`, [letterId],
        );
      }
      await client.query('COMMIT');
      return { hidden, reportCount: total.rows[0].count };
    } catch (error) {
      await client.query('ROLLBACK');
      throw error;
    } finally {
      if (this.db instanceof Pool) client.release();
    }
  }
}
