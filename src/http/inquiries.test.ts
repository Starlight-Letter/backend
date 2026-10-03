import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { Pool, type PoolClient } from 'pg';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { applyMigrations } from '../db/migrations';
import { PostgresAuthService } from '../domains/auth';
import { PostgresInquiryService } from '../domains/inquiries';
import { createApp } from './app';

const databaseUrl = process.env.TEST_DATABASE_URL;
if (databaseUrl && !decodeURIComponent(new URL(databaseUrl).pathname).endsWith('_test')) {
  throw new Error('TEST_DATABASE_URL must point to a database ending in _test');
}
const describeWithDb = databaseUrl ? describe : describe.skip;
const origin = 'http://localhost:5173';

describeWithDb('private inquiry API', () => {
  let pool: Pool;
  let servicePool: Pool;
  let client: PoolClient;
  let app: ReturnType<typeof createApp>;
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
    servicePool = new Pool({ connectionString: databaseUrl, options: `-c search_path=${schema}` });
    app = createApp({
      auth: new PostgresAuthService(servicePool),
      inquiries: new PostgresInquiryService(servicePool),
      allowedOrigins: [origin], secureCookies: false,
    });
    const signup = await post({ nickname: '문의회원', password: 'long-password-123' }, '/api/v1/auth/signup');
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

  function post(data: unknown, path = '/api/v1/inquiries', headers: Record<string, string> = {}) {
    return app(new Request(`http://localhost${path}`, {
      method: 'POST', headers: { origin, 'content-type': 'application/json', ...headers },
      body: JSON.stringify(data),
    }));
  }

  it('accepts a guest privacy inquiry privately with an optional reply email', async () => {
    const response = await post({ type: 'privacy', body: '편지에 개인정보가 보여요.', replyEmail: 'guest@example.com' });
    expect(response.status).toBe(201);
    const result = await response.json() as { data: { id: string; status: string } };
    expect(result.data.status).toBe('open');
    expect(JSON.stringify(result)).not.toContain('guest@example.com');
    const stored = await client.query('SELECT body, reply_email, requester_user_id FROM inquiries WHERE id = $1', [result.data.id]);
    expect(stored.rows[0]).toMatchObject({ body: '편지에 개인정보가 보여요.', reply_email: 'guest@example.com', requester_user_id: null });
    expect((await app(new Request(`http://localhost/api/v1/inquiries/${result.data.id}`))).status).toBe(404);
  });

  it('requires login for a self-authored removal request', async () => {
    const input = { type: 'removal', body: '제가 쓴 편지의 제거를 요청합니다.' };
    expect((await post(input)).status).toBe(401);
    const response = await post(input, '/api/v1/inquiries', { cookie, 'x-csrf-token': csrfToken });
    expect(response.status).toBe(201);
    const result = await response.json() as { data: { id: string } };
    const stored = await client.query('SELECT requester_user_id FROM inquiries WHERE id = $1', [result.data.id]);
    expect(stored.rows[0].requester_user_id).toBeTruthy();
    const withdrawn = await post({ password: 'long-password-123' }, '/api/v1/me/withdraw', {
      cookie, 'x-csrf-token': csrfToken,
    });
    expect(withdrawn.status).toBe(200);
    const unlinked = await client.query('SELECT requester_user_id, body FROM inquiries WHERE id = $1', [result.data.id]);
    expect(unlinked.rows[0]).toMatchObject({ requester_user_id: null, body: input.body });
  });

  it('limits repeated anonymous inquiries from one source', async () => {
    const statuses: number[] = [];
    for (let index = 0; index < 6; index += 1) {
      statuses.push((await post({ type: 'suggestion', body: `건의사항 ${index}` }, '/api/v1/inquiries', {
        'x-starlight-client-ip': 'test-source',
      })).status);
    }
    expect(statuses).toEqual([201, 201, 201, 201, 201, 429]);
  });
});
