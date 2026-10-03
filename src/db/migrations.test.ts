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
    expect(migrations.rows[0].count).toBe(1);
  });

  it('rejects a question for a nonexistent bridge', async () => {
    await expect(client.query(
      `INSERT INTO questions (id, bridge_id, body, effective_at, status)
       VALUES ($1, $2, $3, $4, $5)`,
      [randomUUID(), 'missing', '질문', new Date(), 'published'],
    )).rejects.toMatchObject({ code: '23503' });
  });
});
