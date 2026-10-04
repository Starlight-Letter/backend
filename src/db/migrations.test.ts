import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { Pool, type PoolClient } from 'pg';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { applyMigrations } from './migrations';

const databaseUrl = process.env.TEST_DATABASE_URL;
if (databaseUrl && !decodeURIComponent(new URL(databaseUrl).pathname).endsWith('_test')) {
  throw new Error('TEST_DATABASE_URL must point to a database ending in _test');
}
const describeWithDb = databaseUrl ? describe : describe.skip;

describeWithDb('PostgreSQL migrations', () => {
  let pool: Pool;
  let client: PoolClient;
  const schema = `test_${randomUUID().replaceAll('-', '')}`;
  const directory = fileURLToPath(new URL('../../migrations/', import.meta.url));

  beforeAll(async () => {
    pool = new Pool({ connectionString: databaseUrl });
    client = await pool.connect();
    await client.query(`CREATE SCHEMA ${schema}`);
    await client.query(`SET search_path TO ${schema}`);
    await applyMigrations(client, directory);
  });

  afterAll(async () => {
    if (client) {
      await client.query('SET search_path TO public');
      await client.query(`DROP SCHEMA ${schema} CASCADE`);
      client.release();
    }
    if (pool) await pool.end();
  });

  it('migrates an empty database, seeds exactly three bridges, and is repeatable', async () => {
    await applyMigrations(client, directory);
    const { rows } = await client.query('SELECT id, name FROM bridges ORDER BY display_order');
    expect(rows).toEqual([
      { id: 'bongnim', name: '봉림교' },
      { id: 'sillim', name: '신림교' },
      { id: 'sillim2', name: '신림2교' },
    ]);
    const migrations = await client.query('SELECT count(*)::int AS count FROM schema_migrations');
    expect(migrations.rows[0].count).toBe(13);
  });

  it('enforces active nickname uniqueness and allows reuse after withdrawal', async () => {
    const first = randomUUID();
    const second = randomUUID();
    const insert = `INSERT INTO users
      (id, nickname, nickname_key, password_hash, recovery_code_hash, role, status)
      VALUES ($1, '별빛', '별빛', 'hash', 'recovery', 'member', 'active')`;
    await client.query(insert, [first]);
    await expect(client.query(insert, [second])).rejects.toMatchObject({ code: '23505' });
    await client.query(`UPDATE users SET status = 'withdrawn', withdrawn_at = now() WHERE id = $1`, [first]);
    await client.query(insert, [second]);
    const rows = await client.query('SELECT id, status FROM users WHERE nickname_key = $1 ORDER BY created_at, id', ['별빛']);
    expect(rows.rows).toHaveLength(2);
    expect(rows.rows.map((row) => row.status).sort()).toEqual(['active', 'withdrawn']);
  });

  it('rejects a question for a nonexistent bridge', async () => {
    await expect(client.query(
      `INSERT INTO questions (id, bridge_id, body, effective_at, status)
       VALUES ($1, $2, $3, $4, $5)`,
      [randomUUID(), 'missing', '질문', new Date(), 'published'],
    )).rejects.toMatchObject({ code: '23503' });
  });

  it('enforces member letter question linkage and four bottle themes', async () => {
    await expect(client.query(
      `INSERT INTO letters (id, bridge_id, question_id, display_nickname, body, theme, is_seed, status)
       VALUES ($1, 'sillim', NULL, '별빛', '안녕하세요', 1, false, 'published')`,
      [randomUUID()],
    )).rejects.toMatchObject({ code: '23514' });
    await expect(client.query(
      `INSERT INTO letters (id, bridge_id, question_id, display_nickname, body, theme, is_seed, status)
       VALUES ($1, 'sillim', NULL, '별빛', '안녕하세요', 5, true, 'published')`,
      [randomUUID()],
    )).rejects.toMatchObject({ code: '23514' });
  });

  it('has separate reaction and save records for a published letter', async () => {
    const questionId = randomUUID();
    const letterId = randomUUID();
    const userId = randomUUID();
    await client.query(
      `INSERT INTO questions (id, bridge_id, body, effective_at, status)
       VALUES ($1, 'sillim', '질문', now(), 'published')`, [questionId],
    );
    await client.query(
      `INSERT INTO users (id, nickname, nickname_key, password_hash, recovery_code_hash, role, status)
       VALUES ($1, '응원회원', '응원회원', 'hash', 'recovery', 'member', 'active')`, [userId],
    );
    await client.query(
      `INSERT INTO letters (id, bridge_id, question_id, author_user_id, display_nickname, body, theme, is_seed, status)
       VALUES ($1, 'sillim', $2, $3, '응원회원', '힘내요', 1, false, 'published')`,
      [letterId, questionId, userId],
    );
    await client.query(`INSERT INTO reactions (id, letter_id, user_id, active, activated_at)
      VALUES ($1, $2, $3, true, now())`, [randomUUID(), letterId, userId]);
    await expect(client.query(`INSERT INTO reactions (id, letter_id, user_id, active, activated_at)
      VALUES ($1, $2, $3, true, now())`, [randomUUID(), letterId, userId])).rejects.toMatchObject({ code: '23505' });
    await client.query('INSERT INTO saves (user_id, letter_id) VALUES ($1, $2)', [userId, letterId]);
    await expect(client.query('INSERT INTO saves (user_id, letter_id) VALUES ($1, $2)', [userId, letterId]))
      .rejects.toMatchObject({ code: '23505' });
  });

  it('permits only one report from a member for a letter', async () => {
    const member = randomUUID();
    const letter = randomUUID();
    await client.query(`INSERT INTO users (id, nickname, nickname_key, password_hash, recovery_code_hash, role, status)
      VALUES ($1, '신고회원', '신고회원', 'hash', 'recovery', 'member', 'active')`, [member]);
    await client.query(`INSERT INTO letters (id, bridge_id, display_nickname, body, theme, is_seed, status)
      VALUES ($1, 'sillim', '시작별빛', '테스트', 1, true, 'published')`, [letter]);
    const insert = `INSERT INTO reports (id, letter_id, reporter_user_id, round, reason_code, status)
      VALUES ($1, $2, $3, 1, 'inappropriate', 'pending')`;
    await client.query(insert, [randomUUID(), letter, member]);
    await expect(client.query(insert, [randomUUID(), letter, member])).rejects.toMatchObject({ code: '23505' });
  });
});
