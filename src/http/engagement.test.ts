import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { Pool, type PoolClient } from 'pg';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { applyMigrations } from '../db/migrations';
import { PostgresAuthService } from '../domains/auth';
import { PostgresEngagementService } from '../domains/engagement';
import { createApp } from './app';

const databaseUrl = process.env.TEST_DATABASE_URL;
if (databaseUrl && !decodeURIComponent(new URL(databaseUrl).pathname).endsWith('_test')) {
  throw new Error('TEST_DATABASE_URL must point to a database ending in _test');
}
const describeWithDb = databaseUrl ? describe : describe.skip;
const origin = 'http://localhost:5173';

describeWithDb('reaction, save, and current best API', () => {
  let pool: Pool;
  let servicePool: Pool;
  let client: PoolClient;
  let app: ReturnType<typeof createApp>;
  let letterId: string;
  let cookie: string;
  let csrfToken: string;
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
      VALUES ($1, 'sillim', '동네별빛', '오늘도 힘내요', 1, true, 'published')`, [letterId]);
    servicePool = new Pool({ connectionString: databaseUrl, options: `-c search_path=${schema}` });
    app = createApp({
      auth: new PostgresAuthService(servicePool),
      engagement: new PostgresEngagementService(servicePool),
      allowedOrigins: [origin], secureCookies: false,
      now: () => new Date('2026-10-05T01:00:00Z'),
    });
    const signup = await app(new Request('http://localhost/api/v1/auth/signup', {
      method: 'POST', headers: { origin, 'content-type': 'application/json' },
      body: JSON.stringify({ nickname: '반응회원', password: 'long-password-123' }),
    }));
    const result = await signup.json() as { data: { csrfToken: string } };
    cookie = signup.headers.get('set-cookie')!.split(';')[0];
    csrfToken = result.data.csrfToken;
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

  function mutate(method: 'PUT' | 'DELETE', path: string) {
    return app(new Request(`http://localhost${path}`, {
      method, headers: { origin, cookie, 'x-csrf-token': csrfToken },
    }));
  }

  it('toggles one reaction without double counting, while saves stay separate', async () => {
    const reactionPath = `/api/v1/letters/${letterId}/reaction`;
    expect((await mutate('PUT', reactionPath)).status).toBe(200);
    expect((await mutate('PUT', reactionPath)).status).toBe(200);
    const best = await app(new Request('http://localhost/api/v1/best?scope=bridge&bridgeId=sillim&period=week'));
    const ranked = await best.json() as { data: Array<{ id: string; reactionCount: number }> };
    expect(ranked.data).toMatchObject([{ id: letterId, reactionCount: 1 }]);
    expect((await mutate('PUT', `/api/v1/letters/${letterId}/save`)).status).toBe(200);
    const stateResponse = await app(new Request(`http://localhost/api/v1/letters/${letterId}/engagement`, { headers: { cookie } }));
    expect(stateResponse.status).toBe(200);
    expect((await stateResponse.json() as { data: unknown }).data).toEqual({ reacted: true, saved: true, reactionCount: 1 });
    const guestState = await app(new Request(`http://localhost/api/v1/letters/${letterId}/engagement`));
    expect(guestState.status).toBe(401);
    expect((await mutate('DELETE', reactionPath)).status).toBe(200);
    const after = await app(new Request('http://localhost/api/v1/best?scope=bridge&bridgeId=sillim&period=week'));
    expect((await after.json() as { data: unknown[] }).data).toEqual([]);
    expect((await mutate('PUT', reactionPath)).status).toBe(200);
  });

  it('keeps a hidden save as a body-free placeholder and removes it on withdrawal', async () => {
    await client.query(`UPDATE letters SET status = 'hidden', hidden_at = now() WHERE id = $1`, [letterId]);
    const saved = await app(new Request('http://localhost/api/v1/me/saved', { headers: { cookie } }));
    expect(saved.status).toBe(200);
    const data = await saved.json() as { data: Array<Record<string, unknown>> };
    expect(data.data[0]).toMatchObject({ id: letterId, unavailable: true, message: '지금은 볼 수 없는 편지' });
    expect(data.data[0]).not.toHaveProperty('body');
    expect(data.data[0]).not.toHaveProperty('displayNickname');

    const withdrawn = await app(new Request('http://localhost/api/v1/me/withdraw', {
      method: 'POST', headers: { origin, cookie, 'x-csrf-token': csrfToken, 'content-type': 'application/json' },
      body: JSON.stringify({ password: 'long-password-123' }),
    }));
    expect(withdrawn.status).toBe(200);
    const saves = await client.query('SELECT count(*)::int AS count FROM saves');
    expect(saves.rows[0].count).toBe(0);
    const reactions = await client.query('SELECT user_id, active FROM reactions WHERE letter_id = $1', [letterId]);
    expect(reactions.rows[0]).toMatchObject({ user_id: null, active: true });
  });

  it('uses KST boundaries and includes other bridges only in the all-bridge best', async () => {
    const otherId = randomUUID();
    await client.query(`INSERT INTO letters (id, bridge_id, display_nickname, body, theme, is_seed, status)
      VALUES ($1, 'bongnim', '봉림별빛', '지난 일요일의 응원', 2, true, 'published')`, [otherId]);
    await client.query(`INSERT INTO reactions (id, letter_id, user_id, active, activated_at)
      VALUES ($1, $2, NULL, true, '2026-10-04T14:00:00Z')`, [randomUUID(), otherId]);
    const week = await app(new Request('http://localhost/api/v1/best?scope=all&period=week'));
    expect((await week.json() as { data: Array<{ id: string }> }).data.map((item) => item.id)).toEqual([]);
    const month = await app(new Request('http://localhost/api/v1/best?scope=all&period=month'));
    expect((await month.json() as { data: Array<{ id: string }> }).data.map((item) => item.id)).toEqual([otherId]);
    const bridge = await app(new Request('http://localhost/api/v1/best?scope=bridge&bridgeId=sillim&period=month'));
    expect((await bridge.json() as { data: Array<{ id: string }> }).data).toEqual([]);
  });
});
