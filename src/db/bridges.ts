import type { Pool, PoolClient } from 'pg';

export interface BridgeView {
  id: string;
  name: string;
  displayOrder: number;
  currentQuestion: {
    id: string;
    body: string;
    effectiveAt: string;
  } | null;
}

export interface BridgeReader {
  list(now: Date): Promise<BridgeView[]>;
  get(id: string, now: Date): Promise<BridgeView | null>;
}

interface BridgeRow {
  id: string;
  name: string;
  display_order: number;
  question_id: string | null;
  question_body: string | null;
  question_effective_at: Date | null;
}

const SELECT_BRIDGES = `
  SELECT b.id, b.name, b.display_order,
    q.id AS question_id, q.body AS question_body,
    q.effective_at AS question_effective_at
  FROM bridges b
  LEFT JOIN LATERAL (
    SELECT id, body, effective_at
    FROM questions
    WHERE bridge_id = b.id AND status = 'published' AND effective_at <= $1
    ORDER BY effective_at DESC, id DESC
    LIMIT 1
  ) q ON true
  WHERE b.active = true`;

function toBridge(row: BridgeRow): BridgeView {
  return {
    id: row.id,
    name: row.name,
    displayOrder: row.display_order,
    currentQuestion: row.question_id === null ? null : {
      id: row.question_id,
      body: row.question_body!,
      effectiveAt: row.question_effective_at!.toISOString(),
    },
  };
}

export class PostgresBridgeRepository implements BridgeReader {
  constructor(private readonly db: Pool | PoolClient) {}

  async list(now: Date): Promise<BridgeView[]> {
    const result = await this.db.query<BridgeRow>(`${SELECT_BRIDGES} ORDER BY b.display_order`, [now]);
    return result.rows.map(toBridge);
  }

  async get(id: string, now: Date): Promise<BridgeView | null> {
    const result = await this.db.query<BridgeRow>(`${SELECT_BRIDGES} AND b.id = $2`, [now, id]);
    return result.rows[0] ? toBridge(result.rows[0]) : null;
  }
}
