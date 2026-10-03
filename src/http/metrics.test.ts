import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { Pool, type PoolClient } from 'pg';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { applyMigrations } from '../db/migrations';
import { PostgresAuthService } from '../domains/auth';
import { PostgresMetricsService } from '../domains/metrics';
import { totp } from '../domains/totp';
import { createApp } from './app';

const databaseUrl = process.env.TEST_DATABASE_URL;
if (databaseUrl && !decodeURIComponent(new URL(databaseUrl).pathname).endsWith('_test')) {
  throw new Error('TEST_DATABASE_URL must point to a database ending in _test');
}
const describeWithDb = databaseUrl ? describe : describe.skip;
const origin = 'http://localhost:5173';

describeWithDb('anonymous experiment events', () => {
  let pool: Pool;
  let client: PoolClient;
  let app: ReturnType<typeof createApp>;
  let adminCookie: string;
  const schema = `test_${randomUUID().replaceAll('-', '')}`;
  const directory = fileURLToPath(new URL('../../migrations/', import.meta.url));

  beforeAll(async () => {
    pool = new Pool({ connectionString: databaseUrl });
    client = await pool.connect();
    await client.query(`CREATE SCHEMA ${schema}`);
    await client.query(`SET search_path TO ${schema}`);
    await applyMigrations(client, directory);
    const auth = new PostgresAuthService(client, Buffer.alloc(32, 5));
    app = createApp({ auth, metrics: new PostgresMetricsService(client), allowedOrigins: [origin], secureCookies: false });
    const admin = await auth.provisionAdmin('지표운영자', 'admin-password-123');
    const login = await app(new Request('http://localhost/api/v1/admin/auth/login', {
      method: 'POST', headers: { origin, 'content-type': 'application/json' },
      body: JSON.stringify({ nickname: '지표운영자', password: 'admin-password-123', totp: totp(admin.totpSecret, new Date()) }),
    }));
    adminCookie = login.headers.get('set-cookie')!.split(';')[0];
  });

  afterAll(async () => {
    if (client) {
      await client.query('SET search_path TO public');
      await client.query(`DROP SCHEMA ${schema} CASCADE`);
      client.release();
    }
    if (pool) await pool.end();
  });

  it('counts one survey click per event ID and exposes aggregate counts only to an admin', async () => {
    const eventId = randomUUID();
    const post = () => app(new Request('http://localhost/api/v1/events/survey-open', {
      method: 'POST', headers: { origin, 'content-type': 'application/json' },
      body: JSON.stringify({ eventId, bridgeId: 'sillim' }),
    }));
    expect((await post()).status).toBe(201);
    expect((await post()).status).toBe(200);
    expect((await app(new Request('http://localhost/api/v1/admin/metrics'))).status).toBe(401);
    const report = await app(new Request('http://localhost/api/v1/admin/metrics', { headers: { cookie: adminCookie } }));
    expect(report.status).toBe(200);
    const data = (await report.json()) as { data: { surveyOpens: Array<{ bridgeId: string; count: number }> } };
    expect(data.data.surveyOpens).toContainEqual({ bridgeId: 'sillim', count: 1 });
    const columns = await client.query(`SELECT column_name FROM information_schema.columns
      WHERE table_schema = current_schema() AND table_name = 'metric_events' ORDER BY column_name`);
    expect(columns.rows.map((row) => row.column_name)).toEqual(['bridge_id', 'created_at', 'event_type', 'id', 'letter_kind']);
  });

  it('limits repeated anonymous clicks from one source', async () => {
    const statuses: number[] = [];
    for (let index = 0; index < 21; index += 1) {
      const response = await app(new Request('http://localhost/api/v1/events/survey-open', {
        method: 'POST', headers: { origin, 'content-type': 'application/json', 'x-starlight-client-ip': '198.51.100.42' },
        body: JSON.stringify({ eventId: randomUUID(), bridgeId: 'bongnim' }),
      }));
      statuses.push(response.status);
    }
    expect(statuses.slice(0, 20)).toEqual(Array(20).fill(201));
    expect(statuses[20]).toBe(429);
  });

  it('aggregates QR entry and seed-letter opens without storing letter text or identity', async () => {
    const letterId = randomUUID();
    await client.query(`INSERT INTO letters (id, bridge_id, display_nickname, body, theme, is_seed, status)
      VALUES ($1, 'sillim2', '별빛안부', '힘내요', 1, true, 'published')`, [letterId]);
    const post = (eventType: string, extra: object) => app(new Request('http://localhost/api/v1/events', {
      method: 'POST', headers: { origin, 'content-type': 'application/json' },
      body: JSON.stringify({ eventId: randomUUID(), eventType, ...extra }),
    }));
    expect((await post('qr_entry', { bridgeId: 'sillim2' })).status).toBe(201);
    expect((await post('letter_open', { letterId })).status).toBe(201);
    const report = await app(new Request('http://localhost/api/v1/admin/metrics', { headers: { cookie: adminCookie } }));
    const data = (await report.json()) as { data: { eventsByBridge: Array<{ bridgeId: string; eventType: string; letterKind: string | null; count: number }> } };
    expect(data.data.eventsByBridge).toContainEqual({ bridgeId: 'sillim2', eventType: 'qr_entry', letterKind: null, count: 1 });
    expect(data.data.eventsByBridge).toContainEqual({ bridgeId: 'sillim2', eventType: 'letter_open', letterKind: 'seed', count: 1 });
    const stored = await client.query('SELECT * FROM metric_events WHERE event_type = $1', ['letter_open']);
    expect(stored.rows[0]).not.toHaveProperty('letter_id');
    expect(stored.rows[0]).not.toHaveProperty('body');
  });
});
