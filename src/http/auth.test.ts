import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { Pool, type PoolClient } from 'pg';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { applyMigrations } from '../db/migrations';
import { PostgresAuthService } from '../domains/auth';
import { totp } from '../domains/totp';
import { createApp } from './app';

const databaseUrl = process.env.TEST_DATABASE_URL;
if (databaseUrl && !decodeURIComponent(new URL(databaseUrl).pathname).endsWith('_test')) {
  throw new Error('TEST_DATABASE_URL must point to a database ending in _test');
}
const describeWithDb = databaseUrl ? describe : describe.skip;
const origin = 'http://localhost:5173';

describeWithDb('account API backed by PostgreSQL', () => {
  let pool: Pool;
  let authPool: Pool;
  let client: PoolClient;
  let app: ReturnType<typeof createApp>;
  const schema = `test_${randomUUID().replaceAll('-', '')}`;
  const directory = fileURLToPath(new URL('../../migrations/', import.meta.url));

  beforeAll(async () => {
    pool = new Pool({ connectionString: databaseUrl });
    client = await pool.connect();
    await client.query(`CREATE SCHEMA ${schema}`);
    await client.query(`SET search_path TO ${schema}`);
    await applyMigrations(client, directory);
    authPool = new Pool({ connectionString: databaseUrl, options: `-c search_path=${schema}` });
    app = createApp({ auth: new PostgresAuthService(authPool), allowedOrigins: [origin], secureCookies: false });
  });

  afterAll(async () => {
    if (authPool) await authPool.end();
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

  it('signs up with nickname and password, returns one recovery code, and authenticates by cookie', async () => {
    const signup = await post('/api/v1/auth/signup', { nickname: '별빛회원', password: 'long-password-123' });
    expect(signup.status).toBe(201);
    const result = await signup.json() as { data: { user: { id: string; nickname: string }; recoveryCode: string; csrfToken: string } };
    expect(result.data.user.nickname).toBe('별빛회원');
    expect(result.data.recoveryCode.length).toBeGreaterThan(30);
    const cookie = signup.headers.get('set-cookie') ?? '';
    expect(cookie).toContain('HttpOnly');
    expect(cookie).toContain('SameSite=Lax');
    const me = await app(new Request('http://localhost/api/v1/me', { headers: { cookie: cookie.split(';')[0] } }));
    expect(me.status).toBe(200);
    const meData = await me.json() as { data: { user: { id: string }; csrfToken: string } };
    expect(meData.data.user.id).toBe(result.data.user.id);
    expect(meData.data).not.toHaveProperty('recoveryCode');
    expect(meData.data.csrfToken).toBe(result.data.csrfToken);

    const secondMe = await app(new Request('http://localhost/api/v1/me', { headers: { cookie: cookie.split(';')[0] } }));
    const secondMeData = await secondMe.json() as { data: { csrfToken: string } };
    expect(secondMeData.data.csrfToken).toBe(result.data.csrfToken);

    const wrong = await post('/api/v1/auth/login', { nickname: '별빛회원', password: 'wrong-password' });
    expect(wrong.status).toBe(401);
    const login = await post('/api/v1/auth/login', { nickname: '별빛회원', password: 'long-password-123' });
    expect(login.status).toBe(200);
  });

  it('requires origin and CSRF, invalidates sessions on withdrawal, and permits nickname reuse', async () => {
    const signup = await post('/api/v1/auth/signup', { nickname: '다시사용', password: 'long-password-123' });
    const created = await signup.json() as { data: { user: { id: string }; csrfToken: string } };
    const cookie = signup.headers.get('set-cookie')!.split(';')[0];
    const duplicate = await post('/api/v1/auth/signup', { nickname: '다시사용', password: 'long-password-456' });
    expect(duplicate.status).toBe(409);

    const missingCsrf = await post('/api/v1/me/withdraw', { password: 'long-password-123' }, { cookie });
    expect(missingCsrf.status).toBe(403);
    const wrongOrigin = await post('/api/v1/me/withdraw', { password: 'long-password-123' }, {
      cookie, origin: 'https://evil.example', 'x-csrf-token': created.data.csrfToken,
    });
    expect(wrongOrigin.status).toBe(403);
    const withdraw = await post('/api/v1/me/withdraw', { password: 'long-password-123' }, {
      cookie, 'x-csrf-token': created.data.csrfToken,
    });
    expect(withdraw.status).toBe(200);
    expect((await app(new Request('http://localhost/api/v1/me', { headers: { cookie } }))).status).toBe(401);
    const signupAgain = await post('/api/v1/auth/signup', { nickname: '다시사용', password: 'long-password-789' });
    expect(signupAgain.status).toBe(201);
    const next = await signupAgain.json() as { data: { user: { id: string } } };
    expect(next.data.user.id).not.toBe(created.data.user.id);
  });

  it('uses the recovery code once, rotates it, and revokes previous sessions', async () => {
    const signup = await post('/api/v1/auth/signup', { nickname: '복구회원', password: 'long-password-123' });
    const created = await signup.json() as { data: { recoveryCode: string } };
    const oldCookie = signup.headers.get('set-cookie')!.split(';')[0];
    const recover = await post('/api/v1/auth/recover', {
      nickname: '복구회원', recoveryCode: created.data.recoveryCode, newPassword: 'new-password-123',
    });
    expect(recover.status).toBe(200);
    const recovered = await recover.json() as { data: { recoveryCode: string } };
    expect(recovered.data.recoveryCode).not.toBe(created.data.recoveryCode);
    expect((await app(new Request('http://localhost/api/v1/me', { headers: { cookie: oldCookie } }))).status).toBe(401);
    const reuse = await post('/api/v1/auth/recover', {
      nickname: '복구회원', recoveryCode: created.data.recoveryCode, newPassword: 'third-password-123',
    });
    expect(reuse.status).toBe(401);
    const login = await post('/api/v1/auth/login', { nickname: '복구회원', password: 'new-password-123' });
    expect(login.status).toBe(200);
  });

  it('accepts only one of two concurrent uses of the same recovery code', async () => {
    const signup = await post('/api/v1/auth/signup', { nickname: '동시복구', password: 'long-password-123' });
    const created = await signup.json() as { data: { recoveryCode: string } };
    const input = { nickname: '동시복구', recoveryCode: created.data.recoveryCode, newPassword: 'new-password-123' };
    const [first, second] = await Promise.all([
      post('/api/v1/auth/recover', input),
      post('/api/v1/auth/recover', input),
    ]);
    expect([first.status, second.status].sort()).toEqual([200, 401]);
  });

  it('rate limits repeated password guesses for one nickname', async () => {
    const signup = await post('/api/v1/auth/signup', { nickname: '추측방지', password: 'long-password-123' });
    expect(signup.status).toBe(201);
    const statuses: number[] = [];
    for (let index = 0; index < 6; index += 1) {
      statuses.push((await post('/api/v1/auth/login', { nickname: '추측방지', password: 'wrong-password' })).status);
    }
    expect(statuses).toEqual([401, 401, 401, 401, 401, 429]);
  });

  it('rotates the recovery code after password confirmation and logs out', async () => {
    const signup = await post('/api/v1/auth/signup', { nickname: '코드교체', password: 'long-password-123' });
    const created = await signup.json() as { data: { recoveryCode: string; csrfToken: string } };
    const cookie = signup.headers.get('set-cookie')!.split(';')[0];
    const rotate = await post('/api/v1/me/recovery-code/rotate', { password: 'long-password-123' }, {
      cookie, 'x-csrf-token': created.data.csrfToken,
    });
    expect(rotate.status).toBe(200);
    const rotated = await rotate.json() as { data: { recoveryCode: string } };
    expect(rotated.data.recoveryCode).not.toBe(created.data.recoveryCode);
    const oldCode = await post('/api/v1/auth/recover', {
      nickname: '코드교체', recoveryCode: created.data.recoveryCode, newPassword: 'new-password-123',
    });
    expect(oldCode.status).toBe(401);
    const logout = await post('/api/v1/auth/logout', {}, { cookie, 'x-csrf-token': created.data.csrfToken });
    expect(logout.status).toBe(200);
    expect((await app(new Request('http://localhost/api/v1/me', { headers: { cookie } }))).status).toBe(401);
  });

  it('changes the password and revokes the previous session', async () => {
    const signup = await post('/api/v1/auth/signup', { nickname: '비밀번호변경', password: 'long-password-123' });
    const created = await signup.json() as { data: { csrfToken: string } };
    const oldCookie = signup.headers.get('set-cookie')!.split(';')[0];
    const changed = await post('/api/v1/me/password', {
      currentPassword: 'long-password-123', newPassword: 'new-password-456',
    }, { cookie: oldCookie, 'x-csrf-token': created.data.csrfToken });
    expect(changed.status).toBe(200);
    const replacement = await changed.json() as { data: { csrfToken: string } };
    const newCookie = changed.headers.get('set-cookie')!.split(';')[0];
    expect(replacement.data.csrfToken).toHaveLength(43);
    expect((await app(new Request('http://localhost/api/v1/me', { headers: { cookie: oldCookie } }))).status).toBe(401);
    expect((await post('/api/v1/auth/login', { nickname: '비밀번호변경', password: 'long-password-123' })).status).toBe(401);
    expect((await post('/api/v1/auth/login', { nickname: '비밀번호변경', password: 'new-password-456' })).status).toBe(200);
    expect((await app(new Request('http://localhost/api/v1/me', { headers: { cookie: newCookie } }))).status).toBe(200);
  });

  it('rejects an admin through member login and requires a fresh TOTP for an admin session', async () => {
    const admin = new PostgresAuthService(authPool, Buffer.alloc(32, 7));
    const provisioned = await admin.provisionAdmin('별빛운영자', 'admin-password-123');
    const ordinary = await post('/api/v1/auth/login', { nickname: '별빛운영자', password: 'admin-password-123' });
    expect(ordinary.status).toBe(401);

    const adminApp = createApp({ auth: admin, allowedOrigins: [origin], secureCookies: false });
    const login = (totp: string) => adminApp(new Request('http://localhost/api/v1/admin/auth/login', {
      method: 'POST', headers: { origin, 'content-type': 'application/json' },
      body: JSON.stringify({ nickname: '별빛운영자', password: 'admin-password-123', totp }),
    }));
    expect((await login('000000')).status).toBe(401);
    const code = totp(provisioned.totpSecret, new Date());
    const accepted = await login(code);
    expect(accepted.status).toBe(200);
    const lifetime = await client.query<{ hours: number }>(
      `SELECT extract(epoch FROM (s.expires_at - s.created_at))/3600 AS hours
       FROM sessions s JOIN users u ON u.id = s.user_id WHERE u.nickname_key = '별빛운영자'`,
    );
    expect(Number(lifetime.rows[0].hours)).toBeLessThanOrEqual(12);
    expect((await login(code)).status).toBe(401);
    const cookie = accepted.headers.get('set-cookie')!.split(';')[0];
    const me = await adminApp(new Request('http://localhost/api/v1/me', { headers: { cookie } }));
    expect(me.status).toBe(200);

    const replacement = await admin.resetAdminTotp('별빛운영자');
    expect(replacement.totpSecret).not.toBe(provisioned.totpSecret);
    expect((await adminApp(new Request('http://localhost/api/v1/me', { headers: { cookie } }))).status).toBe(401);
    expect((await login(totp(replacement.totpSecret, new Date()))).status).toBe(200);
  });
});
