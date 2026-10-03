import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { Pool, type PoolClient } from 'pg';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { applyMigrations } from '../db/migrations';
import { PostgresAuthService } from '../domains/auth';
import { PostgresLetterService } from '../domains/letters';
import { createApp } from './app';

const databaseUrl = process.env.TEST_DATABASE_URL;
if (databaseUrl && !decodeURIComponent(new URL(databaseUrl).pathname).endsWith('_test')) {
  throw new Error('TEST_DATABASE_URL must point to a database ending in _test');
}
const describeWithDb = databaseUrl ? describe : describe.skip;
const origin = 'http://localhost:5173';

describeWithDb('letter API backed by PostgreSQL', () => {
  let pool: Pool;
  let servicePool: Pool;
  let client: PoolClient;
  let app: ReturnType<typeof createApp>;
  let questionId: string;
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
    questionId = randomUUID();
    await client.query(
      `INSERT INTO questions (id, bridge_id, body, effective_at, status)
       VALUES ($1, 'sillim', '오늘의 질문', '2026-10-04T15:00:00Z', 'published')`,
      [questionId],
    );
    servicePool = new Pool({ connectionString: databaseUrl, options: `-c search_path=${schema}` });
    app = createApp({
      auth: new PostgresAuthService(servicePool),
      letters: new PostgresLetterService(servicePool),
      now: () => new Date('2026-10-05T01:00:00Z'),
      allowedOrigins: [origin],
      secureCookies: false,
    });
    const signup = await post('/api/v1/auth/signup', { nickname: '편지회원', password: 'long-password-123' });
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

  function post(path: string, data: unknown, headers: Record<string, string> = {}) {
    return app(new Request(`http://localhost${path}`, {
      method: 'POST',
      headers: { origin, 'content-type': 'application/json', ...headers },
      body: JSON.stringify(data),
    }));
  }

  it('publishes a member letter once and lets guests read its detail', async () => {
    const headers = { cookie, 'x-csrf-token': csrfToken, 'idempotency-key': 'letter-once' };
    const input = { questionId, body: '오늘도 잘 버텼어요.', theme: 2 };
    const first = await post('/api/v1/bridges/sillim/letters', input, headers);
    expect(first.status).toBe(201);
    const created = await first.json() as { data: { id: string } };
    const repeated = await post('/api/v1/bridges/sillim/letters', input, headers);
    expect((await repeated.json() as { data: { id: string } }).data.id).toBe(created.data.id);
    const conflict = await post('/api/v1/bridges/sillim/letters', { ...input, body: '다른 글' }, headers);
    expect(conflict.status).toBe(409);
    const detail = await app(new Request(`http://localhost/api/v1/letters/${created.data.id}`));
    expect(detail.status).toBe(200);
    expect(await detail.json()).toMatchObject({ data: { body: input.body, displayNickname: '편지회원', bridgeId: 'sillim' } });
  });

  it('rejects a stale question, inappropriate content, and guest writing', async () => {
    const headers = { cookie, 'x-csrf-token': csrfToken, 'idempotency-key': 'stale-question' };
    expect((await post('/api/v1/bridges/sillim/letters', {
      questionId: randomUUID(), body: '안녕하세요', theme: 1,
    }, headers)).status).toBe(409);
    expect((await post('/api/v1/bridges/sillim/letters', {
      questionId, body: 'http://spam.example 홍보', theme: 1,
    }, { ...headers, 'idempotency-key': 'advertising' })).status).toBe(422);
    expect((await post('/api/v1/bridges/sillim/letters', {
      questionId, body: '안녕하세요', theme: 1,
    }, { 'idempotency-key': 'guest' })).status).toBe(401);
  });

  it('mixes only this bridge’s current member letters and seed letters in the river', async () => {
    await client.query(
      `INSERT INTO letters (id, bridge_id, question_id, display_nickname, body, theme, is_seed, status)
       VALUES ($1, 'sillim', NULL, '동네별빛', '당신을 응원해요', 1, true, 'published'),
              ($2, 'bongnim', NULL, '다른다리', '다른 다리의 편지', 1, true, 'published')`,
      [randomUUID(), randomUUID()],
    );
    const response = await app(new Request('http://localhost/api/v1/bridges/sillim/river'));
    expect(response.status).toBe(200);
    const result = await response.json() as { data: Array<{ bridgeId: string; preview: string; isSeed: boolean }> };
    expect(result.data.length).toBeGreaterThan(0);
    expect(result.data.length).toBeLessThanOrEqual(5);
    expect(result.data.every((letter) => letter.bridgeId === 'sillim')).toBe(true);
    expect(result.data.some((letter) => letter.isSeed)).toBe(true);
    expect(result.data.every((letter) => letter.preview.length < 151)).toBe(true);
  });

  it('keeps a sent letter and signature but removes its author link after withdrawal', async () => {
    const signup = await post('/api/v1/auth/signup', { nickname: '떠난회원', password: 'long-password-123' });
    const account = await signup.json() as { data: { csrfToken: string } };
    const ownCookie = signup.headers.get('set-cookie')!.split(';')[0];
    const headers = { cookie: ownCookie, 'x-csrf-token': account.data.csrfToken };
    const created = await post('/api/v1/bridges/sillim/letters', {
      questionId, body: '이 편지는 남아 있어요.', theme: 3,
    }, { ...headers, 'idempotency-key': 'withdraw-letter' });
    const letter = await created.json() as { data: { id: string } };
    expect(created.status).toBe(201);
    const withdrawn = await post('/api/v1/me/withdraw', { password: 'long-password-123' }, headers);
    expect(withdrawn.status).toBe(200);
    const detail = await app(new Request(`http://localhost/api/v1/letters/${letter.data.id}`));
    expect(await detail.json()).toMatchObject({ data: { body: '이 편지는 남아 있어요.', displayNickname: '떠난회원' } });
    const links = await client.query('SELECT author_user_id FROM letters WHERE id = $1', [letter.data.id]);
    expect(links.rows[0].author_user_id).toBeNull();
    const keys = await client.query('SELECT count(*)::int AS count FROM request_keys WHERE letter_id = $1', [letter.data.id]);
    expect(keys.rows[0].count).toBe(0);
  });
});
