import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { Pool, type PoolClient } from 'pg';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { applyMigrations } from '../db/migrations';
import { PostgresBridgeRepository } from '../db/bridges';
import { createApp } from './app';

const databaseUrl = process.env.TEST_DATABASE_URL;
if (databaseUrl && !decodeURIComponent(new URL(databaseUrl).pathname).endsWith('_test')) {
  throw new Error('TEST_DATABASE_URL must point to a database ending in _test');
}
const describeWithDb = databaseUrl ? describe : describe.skip;

describeWithDb('public bridge API backed by PostgreSQL', () => {
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

  it('lists exactly three bridges in display order', async () => {
    const app = createApp({ bridges: new PostgresBridgeRepository(client) });
    const response = await app(new Request('http://localhost/api/v1/bridges'));
    expect(response.status).toBe(200);
    const result = await response.json() as { data: Array<{ id: string; currentQuestion: unknown }> };
    expect(result.data.map((bridge: { id: string }) => bridge.id)).toEqual(['bongnim', 'sillim', 'sillim2']);
    expect(result.data[0].currentQuestion).toBeNull();
  });

  it('switches at the KST Monday boundary and keeps the previous question if no new one exists', async () => {
    const old = randomUUID();
    const next = randomUUID();
    await client.query(
      `INSERT INTO questions (id, bridge_id, body, effective_at, status) VALUES
       ($1, 'sillim', '지난 질문', '2026-09-27T15:00:00Z', 'published'),
       ($2, 'sillim', '새 질문', '2026-10-04T15:00:00Z', 'published'),
       ($3, 'sillim', '초안', '2026-10-04T14:00:00Z', 'draft'),
       ($4, 'bongnim', '다른 다리 질문', '2026-10-04T15:00:00Z', 'published')`,
      [old, next, randomUUID(), randomUUID()],
    );
    const repository = new PostgresBridgeRepository(client);
    const before = createApp({ bridges: repository, now: () => new Date('2026-10-04T14:59:59Z') });
    const after = createApp({ bridges: repository, now: () => new Date('2026-10-11T15:00:00Z') });
    const beforeData = await (await before(new Request('http://localhost/api/v1/bridges/sillim'))).json() as { data: { currentQuestion: unknown } };
    const afterData = await (await after(new Request('http://localhost/api/v1/bridges/sillim'))).json() as { data: { currentQuestion: unknown } };
    expect(beforeData.data.currentQuestion).toMatchObject({ id: old, body: '지난 질문' });
    expect(afterData.data.currentQuestion).toMatchObject({ id: next, body: '새 질문' });

    await client.query('UPDATE questions SET body = $2, updated_at = now() WHERE id = $1', [next, '즉시 수정한 질문']);
    const corrected = await (await after(new Request('http://localhost/api/v1/bridges/sillim'))).json() as { data: { currentQuestion: unknown } };
    expect(corrected.data.currentQuestion).toMatchObject({ id: next, body: '즉시 수정한 질문' });
  });

  it('returns 404 for an unknown bridge without querying another bridge', async () => {
    const app = createApp({ bridges: new PostgresBridgeRepository(client) });
    const response = await app(new Request('http://localhost/api/v1/bridges/not-a-bridge'));
    expect(response.status).toBe(404);
    expect(await response.json()).toEqual({ error: { code: 'BRIDGE_NOT_FOUND', message: '다리를 찾을 수 없습니다.' } });
  });
});
