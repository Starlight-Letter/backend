import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { Pool, type PoolClient } from 'pg';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { applyMigrations } from '../db/migrations';
import { PostgresAuthService } from '../domains/auth';
import { PostgresLetterService } from '../domains/letters';
import { PostgresReportService } from '../domains/reports';
import { createApp } from './app';

const databaseUrl = process.env.TEST_DATABASE_URL;
if (databaseUrl && !decodeURIComponent(new URL(databaseUrl).pathname).endsWith('_test')) {
  throw new Error('TEST_DATABASE_URL must point to a database ending in _test');
}
const describeWithDb = databaseUrl ? describe : describe.skip;
const origin = 'http://localhost:5173';

describeWithDb('member reports and automatic hiding', () => {
  let pool: Pool;
  let servicePool: Pool;
  let client: PoolClient;
  let app: ReturnType<typeof createApp>;
  let letterId: string;
  let members: Array<{ cookie: string; csrfToken: string }>;
  const schema = `test_${randomUUID().replaceAll('-', '')}`;
  const directory = fileURLToPath(new URL('../../migrations/', import.meta.url));

  beforeAll(async () => {
    pool = new Pool({ connectionString: databaseUrl });
    client = await pool.connect();
    await client.query(`CREATE SCHEMA ${schema}`);
    await client.query(`SET search_path TO ${schema}`);
    await applyMigrations(client, directory);
    letterId = randomUUID();
    await client.query(`INSERT INTO letters (id, bridge_id, display_nickname, body, theme, is_seed, status)
      VALUES ($1, 'sillim', '시작별빛', '이 편지를 확인해요', 1, true, 'published')`, [letterId]);
    servicePool = new Pool({ connectionString: databaseUrl, options: `-c search_path=${schema}` });
    app = createApp({
      auth: new PostgresAuthService(servicePool),
      letters: new PostgresLetterService(servicePool),
      reports: new PostgresReportService(servicePool),
      allowedOrigins: [origin], secureCookies: false,
    });
    members = [];
    for (let index = 0; index < 6; index += 1) {
      const signup = await app(new Request('http://localhost/api/v1/auth/signup', {
        method: 'POST', headers: { origin, 'content-type': 'application/json' },
        body: JSON.stringify({ nickname: `신고회원${index}`, password: 'long-password-123' }),
      }));
      const result = await signup.json() as { data: { csrfToken: string } };
      members.push({ cookie: signup.headers.get('set-cookie')!.split(';')[0], csrfToken: result.data.csrfToken });
    }
  });

  afterAll(async () => {
    if (servicePool) await servicePool.end();
    if (client) {
      await client.query('SET search_path TO public');
      await client.query(`DROP SCHEMA ${schema} CASCADE`);
      client.release();
    }
    if (pool) await pool.end();
  });

  function report(index: number, id = letterId) {
    return app(new Request(`http://localhost/api/v1/letters/${id}/reports`, {
      method: 'POST',
      headers: { origin, cookie: members[index].cookie, 'x-csrf-token': members[index].csrfToken,
        'content-type': 'application/json' },
      body: JSON.stringify({ reasonCode: 'inappropriate' }),
    }));
  }

  it('shows one report to operators without hiding until five distinct accounts report', async () => {
    for (let index = 0; index < 4; index += 1) {
      const response = await report(index);
      expect(response.status).toBe(201);
      expect((await response.json() as { data: { hidden: boolean } }).data.hidden).toBe(false);
    }
    expect((await report(0)).status).toBe(409);
    expect((await app(new Request(`http://localhost/api/v1/letters/${letterId}`))).status).toBe(200);
    const fifth = await report(4);
    expect(fifth.status).toBe(201);
    expect((await fifth.json() as { data: { hidden: boolean } }).data.hidden).toBe(true);
    expect((await app(new Request(`http://localhost/api/v1/letters/${letterId}`))).status).toBe(404);
  });

  it('serializes two simultaneous reports at the 4-to-5 threshold', async () => {
    const target = randomUUID();
    await client.query(`INSERT INTO letters (id, bridge_id, display_nickname, body, theme, is_seed, status)
      VALUES ($1, 'sillim', '시작별빛', '또 다른 편지', 1, true, 'published')`, [target]);
    for (let index = 0; index < 4; index += 1) expect((await report(index, target)).status).toBe(201);
    const [fifth, sixth] = await Promise.all([report(4, target), report(5, target)]);
    expect([fifth.status, sixth.status].sort()).toEqual([201, 404]);
    const state = await client.query(`SELECT status FROM letters WHERE id = $1`, [target]);
    const count = await client.query(`SELECT count(*)::int AS count FROM reports WHERE letter_id = $1`, [target]);
    expect(state.rows[0].status).toBe('hidden');
    expect(count.rows[0].count).toBe(5);
  });

  it('keeps report history while removing the reporter account link on withdrawal', async () => {
    const withdrawn = await app(new Request('http://localhost/api/v1/me/withdraw', {
      method: 'POST',
      headers: { origin, cookie: members[0].cookie, 'x-csrf-token': members[0].csrfToken,
        'content-type': 'application/json' },
      body: JSON.stringify({ password: 'long-password-123' }),
    }));
    expect(withdrawn.status).toBe(200);
    const result = await client.query(`SELECT reporter_user_id FROM reports WHERE letter_id = $1
      ORDER BY created_at LIMIT 1`, [letterId]);
    expect(result.rows[0].reporter_user_id).toBeNull();
    const count = await client.query('SELECT count(*)::int AS count FROM reports WHERE letter_id = $1', [letterId]);
    expect(count.rows[0].count).toBe(5);
  });
});
