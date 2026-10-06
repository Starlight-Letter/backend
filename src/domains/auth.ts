import { createCipheriv, createDecipheriv, createHash, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import argon2 from 'argon2';
import { Pool, type PoolClient } from 'pg';
import { newTotpSecret, verifyTotp } from './totp';

const HASH_OPTIONS = { type: argon2.argon2id, memoryCost: 19_456, timeCost: 2, parallelism: 1 } as const;
const dummyHash = argon2.hash(randomBytes(32).toString('base64url'), HASH_OPTIONS);

interface UserRow {
  id: string;
  nickname: string;
  nickname_key: string;
  password_hash: string;
  recovery_code_hash: string;
  role: 'member' | 'admin';
  status: 'active' | 'withdrawn';
  totp_secret_ciphertext: string | null;
  last_totp_step: string;
}

export interface PublicUser {
  id: string;
  nickname: string;
  role: 'member' | 'admin';
}

interface SessionRow extends UserRow {
  session_id: string;
  csrf_hash: string;
  csrf_token: string;
  admin_verified_at: Date | null;
}

export class AuthError extends Error {
  constructor(public readonly status: number, public readonly code: string, message: string) {
    super(message);
  }
}

const digest = (value: string) => createHash('sha256').update(value).digest('hex');
const token = () => randomBytes(32).toString('base64url');
const safeEqual = (a: string, b: string) => {
  const first = Buffer.from(a, 'hex');
  const second = Buffer.from(b, 'hex');
  return first.length === second.length && timingSafeEqual(first, second);
};
const publicUser = (user: UserRow): PublicUser => ({ id: user.id, nickname: user.nickname, role: user.role });

function nicknameParts(input: unknown): { nickname: string; key: string } {
  if (typeof input !== 'string') throw new AuthError(400, 'INVALID_NICKNAME', '닉네임을 확인해 주세요.');
  const nickname = input.trim().normalize('NFC');
  const length = Array.from(nickname).length;
  if (length < 2 || length > 20 || /[\x00-\x1f\x7f]/u.test(nickname)) {
    throw new AuthError(400, 'INVALID_NICKNAME', '닉네임은 2~20자로 입력해 주세요.');
  }
  return { nickname, key: nickname.toLocaleLowerCase('ko-KR') };
}

function validPassword(input: unknown): string {
  if (typeof input !== 'string' || input.length < 8 || input.length > 128) {
    throw new AuthError(400, 'INVALID_PASSWORD', '비밀번호는 8~128자로 입력해 주세요.');
  }
  return input;
}

export class PostgresAuthService {
  constructor(private readonly db: Pool | PoolClient, private readonly adminTotpKey?: Buffer) {
    if (adminTotpKey && adminTotpKey.length !== 32) throw new Error('Admin TOTP key must be 32 bytes');
  }

  private encryptTotp(secret: string): string {
    if (!this.adminTotpKey) throw new Error('ADMIN_TOTP_KEY is required');
    const iv = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', this.adminTotpKey, iv);
    const ciphertext = Buffer.concat([cipher.update(secret, 'utf8'), cipher.final()]);
    return [iv, cipher.getAuthTag(), ciphertext].map((part) => part.toString('base64url')).join('.');
  }

  private decryptTotp(value: string): string {
    if (!this.adminTotpKey) throw new Error('ADMIN_TOTP_KEY is required');
    const [iv, tag, ciphertext] = value.split('.').map((part) => Buffer.from(part, 'base64url'));
    const decipher = createDecipheriv('aes-256-gcm', this.adminTotpKey, iv);
    decipher.setAuthTag(tag);
    return Buffer.concat([decipher.update(ciphertext), decipher.final()]).toString('utf8');
  }

  async provisionAdmin(nicknameInput: unknown, passwordInput: unknown) {
    const { nickname, key } = nicknameParts(nicknameInput);
    const password = validPassword(passwordInput);
    const totpSecret = newTotpSecret();
    const passwordHash = await argon2.hash(password, HASH_OPTIONS);
    const recoveryHash = await argon2.hash(token(), HASH_OPTIONS);
    const ciphertext = this.encryptTotp(totpSecret);
    const id = randomUUID();
    await this.db.query(
      `INSERT INTO users (id, nickname, nickname_key, password_hash, recovery_code_hash, role, status, totp_secret_ciphertext)
       VALUES ($1, $2, $3, $4, $5, 'admin', 'active', $6)`,
      [id, nickname, key, passwordHash, recoveryHash, ciphertext],
    );
    return { id, totpSecret };
  }

  async resetAdminTotp(nicknameInput: unknown) {
    const { key } = nicknameParts(nicknameInput);
    const totpSecret = newTotpSecret();
    const ciphertext = this.encryptTotp(totpSecret);
    await this.transaction(async (client) => {
      const found = await client.query<{ id: string }>(
        `SELECT id FROM users WHERE nickname_key = $1 AND role = 'admin' AND status = 'active' FOR UPDATE`, [key],
      );
      if (!found.rows[0]) throw new AuthError(404, 'ADMIN_NOT_FOUND', '운영자 계정을 찾을 수 없습니다.');
      await client.query(
        `UPDATE users SET totp_secret_ciphertext = $1, last_totp_step = -1 WHERE id = $2`,
        [ciphertext, found.rows[0].id],
      );
      await client.query('UPDATE sessions SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL',
        [found.rows[0].id]);
    });
    return { totpSecret };
  }

  private async consumeAttempt(scope: string, subject: string, maximum: number): Promise<void> {
    const key = digest(`${scope}:${subject}`);
    const result = await this.db.query<{ attempts: number }>(
      `INSERT INTO auth_attempts (key_hash, attempts, window_started_at)
       VALUES ($1, 1, now())
       ON CONFLICT (key_hash) DO UPDATE SET
         attempts = CASE WHEN auth_attempts.window_started_at < now() - interval '15 minutes'
           THEN 1 ELSE auth_attempts.attempts + 1 END,
         window_started_at = CASE WHEN auth_attempts.window_started_at < now() - interval '15 minutes'
           THEN now() ELSE auth_attempts.window_started_at END
       RETURNING attempts`,
      [key],
    );
    if (result.rows[0].attempts > maximum) {
      throw new AuthError(429, 'RATE_LIMITED', '잠시 후 다시 시도해 주세요.');
    }
  }

  private async clearAttempts(scope: string, subject: string): Promise<void> {
    await this.db.query('DELETE FROM auth_attempts WHERE key_hash = $1', [digest(`${scope}:${subject}`)]);
  }

  private async transaction<T>(run: (client: PoolClient) => Promise<T>): Promise<T> {
    const client = this.db instanceof Pool ? await this.db.connect() : this.db;
    try {
      await client.query('BEGIN');
      const result = await run(client);
      await client.query('COMMIT');
      return result;
    } catch (error) {
      await client.query('ROLLBACK');
      throw error;
    } finally {
      if (this.db instanceof Pool) client.release();
    }
  }

  private async newSession(client: PoolClient, userId: string, adminVerified = false) {
    const sessionToken = token();
    const csrfToken = token();
    await client.query(
      `INSERT INTO sessions (id, user_id, token_hash, csrf_hash, csrf_token, expires_at, admin_verified_at)
       VALUES ($1, $2, $3, $4, $5, now() + CASE WHEN $6 THEN interval '12 hours' ELSE interval '30 days' END,
         CASE WHEN $6 THEN now() ELSE NULL END)`,
      [randomUUID(), userId, digest(sessionToken), digest(csrfToken), csrfToken, adminVerified],
    );
    return { sessionToken, csrfToken };
  }

  async signup(nicknameInput: unknown, passwordInput: unknown, source = 'unknown') {
    await this.consumeAttempt('signup-source', source, 10);
    const { nickname, key } = nicknameParts(nicknameInput);
    const password = validPassword(passwordInput);
    const passwordHash = await argon2.hash(password, HASH_OPTIONS);
    const recoveryCode = token();
    const recoveryHash = await argon2.hash(recoveryCode, HASH_OPTIONS);
    try {
      return await this.transaction(async (client) => {
        const id = randomUUID();
        await client.query(
          `INSERT INTO users (id, nickname, nickname_key, password_hash, recovery_code_hash, role, status)
           VALUES ($1, $2, $3, $4, $5, 'member', 'active')`,
          [id, nickname, key, passwordHash, recoveryHash],
        );
        const session = await this.newSession(client, id);
        return { user: { id, nickname, role: 'member' as const }, recoveryCode, ...session };
      });
    } catch (error) {
      if (typeof error === 'object' && error !== null && 'code' in error && error.code === '23505') {
        throw new AuthError(409, 'NICKNAME_TAKEN', '이미 사용 중인 닉네임입니다.');
      }
      throw error;
    }
  }

  async login(nicknameInput: unknown, passwordInput: unknown, source = 'unknown') {
    const { key } = nicknameParts(nicknameInput);
    const password = validPassword(passwordInput);
    await this.consumeAttempt('login-account', key, 5);
    await this.consumeAttempt('login-source', source, 60);
    const result = await this.db.query<UserRow>(
      `SELECT * FROM users WHERE nickname_key = $1 AND status = 'active'`, [key],
    );
    const user = result.rows[0];
    const hash = user?.password_hash ?? await dummyHash;
    if (!await argon2.verify(hash, password) || !user || user.role !== 'member') {
      throw new AuthError(401, 'INVALID_CREDENTIALS', '닉네임 또는 비밀번호를 확인해 주세요.');
    }
    const session = await this.transaction((client) => this.newSession(client, user.id));
    await this.clearAttempts('login-account', key);
    return { user: publicUser(user), ...session };
  }

  async adminLogin(nicknameInput: unknown, passwordInput: unknown, totpInput: unknown, source = 'unknown') {
    if (!this.adminTotpKey) throw new AuthError(503, 'SERVICE_UNAVAILABLE', '운영자 로그인을 이용할 수 없습니다.');
    const { key } = nicknameParts(nicknameInput);
    const password = validPassword(passwordInput);
    await this.consumeAttempt('admin-login-account', key, 5);
    await this.consumeAttempt('admin-login-source', source, 30);
    const result = await this.transaction(async (client) => {
      const found = await client.query<UserRow>(
        `SELECT * FROM users WHERE nickname_key = $1 AND status = 'active' FOR UPDATE`, [key],
      );
      const user = found.rows[0];
      const hash = user?.password_hash ?? await dummyHash;
      if (!await argon2.verify(hash, password) || !user || user.role !== 'admin' || !user.totp_secret_ciphertext) {
        throw new AuthError(401, 'INVALID_CREDENTIALS', '로그인 정보를 확인해 주세요.');
      }
      const secret = this.decryptTotp(user.totp_secret_ciphertext);
      const step = verifyTotp(secret, totpInput, new Date(), Number(user.last_totp_step));
      if (step === null) throw new AuthError(401, 'INVALID_CREDENTIALS', '로그인 정보를 확인해 주세요.');
      await client.query('UPDATE users SET last_totp_step = $1 WHERE id = $2', [step, user.id]);
      const session = await this.newSession(client, user.id, true);
      return { user: publicUser(user), ...session };
    });
    await this.clearAttempts('admin-login-account', key);
    return result;
  }

  private async session(sessionToken: string | null): Promise<SessionRow> {
    if (!sessionToken || !/^[A-Za-z0-9_-]{43}$/.test(sessionToken)) {
      throw new AuthError(401, 'LOGIN_REQUIRED', '로그인이 필요합니다.');
    }
    const result = await this.db.query<SessionRow>(
      `SELECT s.id AS session_id, s.csrf_hash, s.csrf_token, s.admin_verified_at, u.*
       FROM sessions s JOIN users u ON u.id = s.user_id
       WHERE s.token_hash = $1 AND s.revoked_at IS NULL AND s.expires_at > now()
         AND u.status = 'active'`,
      [digest(sessionToken)],
    );
    if (!result.rows[0]) throw new AuthError(401, 'LOGIN_REQUIRED', '로그인이 필요합니다.');
    return result.rows[0];
  }

  async authenticate(sessionToken: string | null): Promise<PublicUser> {
    return publicUser(await this.session(sessionToken));
  }

  async authorizeMutation(sessionToken: string | null, csrfToken: string | null): Promise<PublicUser> {
    const session = await this.session(sessionToken);
    if (!csrfToken || !safeEqual(digest(csrfToken), session.csrf_hash)) {
      throw new AuthError(403, 'CSRF_REQUIRED', '요청 인증 정보가 올바르지 않습니다.');
    }
    if (session.role !== 'member') throw new AuthError(403, 'MEMBER_REQUIRED', '회원 기능을 이용할 수 없습니다.');
    return publicUser(session);
  }

  async authorizeAdmin(sessionToken: string | null, csrfToken: string | null): Promise<PublicUser> {
    const session = await this.session(sessionToken);
    if (session.role !== 'admin' || !session.admin_verified_at) {
      throw new AuthError(403, 'ADMIN_REQUIRED', '운영자 권한이 필요합니다.');
    }
    if (!csrfToken || !safeEqual(digest(csrfToken), session.csrf_hash)) {
      throw new AuthError(403, 'CSRF_REQUIRED', '요청 인증 정보가 올바르지 않습니다.');
    }
    return publicUser(session);
  }

  async authenticateAdmin(sessionToken: string | null): Promise<PublicUser> {
    const session = await this.session(sessionToken);
    if (session.role !== 'admin' || !session.admin_verified_at) {
      throw new AuthError(403, 'ADMIN_REQUIRED', '운영자 권한이 필요합니다.');
    }
    return publicUser(session);
  }

  async refreshCsrf(sessionToken: string | null) {
    const session = await this.session(sessionToken);
    if (session.csrf_token) return { user: publicUser(session), csrfToken: session.csrf_token };
    const csrfToken = token();
    await this.db.query('UPDATE sessions SET csrf_hash = $1, csrf_token = $2 WHERE id = $3', [digest(csrfToken), csrfToken, session.session_id]);
    return { user: publicUser(session), csrfToken };
  }

  async logout(sessionToken: string | null, csrfToken: string | null): Promise<void> {
    const session = await this.session(sessionToken);
    if (!csrfToken || !safeEqual(digest(csrfToken), session.csrf_hash)) {
      throw new AuthError(403, 'CSRF_REQUIRED', '요청 인증 정보가 올바르지 않습니다.');
    }
    await this.db.query('UPDATE sessions SET revoked_at = now() WHERE token_hash = $1', [digest(sessionToken!)]);
  }

  async rotateRecoveryCode(sessionToken: string | null, csrfToken: string | null, passwordInput: unknown) {
    const user = await this.authorizeMutation(sessionToken, csrfToken);
    const password = validPassword(passwordInput);
    return this.transaction(async (client) => {
      const found = await client.query<UserRow>(
        `SELECT * FROM users WHERE id = $1 AND status = 'active' FOR UPDATE`, [user.id],
      );
      if (!found.rows[0] || !await argon2.verify(found.rows[0].password_hash, password)) {
        throw new AuthError(401, 'INVALID_CREDENTIALS', '비밀번호를 확인해 주세요.');
      }
      const recoveryCode = token();
      const recoveryHash = await argon2.hash(recoveryCode, HASH_OPTIONS);
      await client.query('UPDATE users SET recovery_code_hash = $1 WHERE id = $2', [recoveryHash, user.id]);
      return { recoveryCode };
    });
  }

  async changePassword(
    sessionToken: string | null,
    csrfToken: string | null,
    currentPasswordInput: unknown,
    newPasswordInput: unknown,
  ) {
    const user = await this.authorizeMutation(sessionToken, csrfToken);
    const currentPassword = validPassword(currentPasswordInput);
    const newPassword = validPassword(newPasswordInput);
    if (currentPassword === newPassword) {
      throw new AuthError(400, 'INVALID_PASSWORD', '새 비밀번호는 현재 비밀번호와 다르게 입력해 주세요.');
    }
    return this.transaction(async (client) => {
      const found = await client.query<UserRow>(
        `SELECT * FROM users WHERE id = $1 AND status = 'active' FOR UPDATE`, [user.id],
      );
      const account = found.rows[0];
      if (!account || !await argon2.verify(account.password_hash, currentPassword)) {
        throw new AuthError(401, 'INVALID_CREDENTIALS', '비밀번호를 확인해 주세요.');
      }
      const passwordHash = await argon2.hash(newPassword, HASH_OPTIONS);
      await client.query('UPDATE users SET password_hash = $1 WHERE id = $2', [passwordHash, user.id]);
      await client.query('UPDATE sessions SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL', [user.id]);
      const session = await this.newSession(client, user.id);
      return { user: publicUser(account), ...session };
    });
  }

  async withdraw(sessionToken: string | null, csrfToken: string | null, passwordInput: unknown): Promise<void> {
    const user = await this.authorizeMutation(sessionToken, csrfToken);
    const password = validPassword(passwordInput);
    const found = await this.db.query<UserRow>('SELECT * FROM users WHERE id = $1', [user.id]);
    if (!await argon2.verify(found.rows[0].password_hash, password)) {
      throw new AuthError(401, 'INVALID_CREDENTIALS', '비밀번호를 확인해 주세요.');
    }
    await this.transaction(async (client) => {
      await client.query(`UPDATE users SET status = 'withdrawn', withdrawn_at = now() WHERE id = $1 AND status = 'active'`, [user.id]);
      await client.query('UPDATE letters SET author_user_id = NULL WHERE author_user_id = $1', [user.id]);
      await client.query('DELETE FROM request_keys WHERE user_id = $1', [user.id]);
      await client.query('UPDATE reactions SET user_id = NULL WHERE user_id = $1', [user.id]);
      await client.query('DELETE FROM saves WHERE user_id = $1', [user.id]);
      await client.query('UPDATE reports SET reporter_user_id = NULL WHERE reporter_user_id = $1', [user.id]);
      await client.query('UPDATE inquiries SET requester_user_id = NULL WHERE requester_user_id = $1', [user.id]);
      await client.query('UPDATE sessions SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL', [user.id]);
    });
  }

  async recover(nicknameInput: unknown, codeInput: unknown, newPasswordInput: unknown, source = 'unknown') {
    const { key } = nicknameParts(nicknameInput);
    const newPassword = validPassword(newPasswordInput);
    if (typeof codeInput !== 'string') throw new AuthError(401, 'INVALID_RECOVERY', '복구 정보를 확인해 주세요.');
    await this.consumeAttempt('recover-account', key, 5);
    await this.consumeAttempt('recover-source', source, 30);
    const result = await this.transaction(async (client) => {
      const result = await client.query<UserRow>(
        `SELECT * FROM users WHERE nickname_key = $1 AND status = 'active' FOR UPDATE`, [key],
      );
      const user = result.rows[0];
      const hash = user?.recovery_code_hash ?? await dummyHash;
      if (!await argon2.verify(hash, codeInput) || !user) {
        throw new AuthError(401, 'INVALID_RECOVERY', '복구 정보를 확인해 주세요.');
      }
      const recoveryCode = token();
      const recoveryHash = await argon2.hash(recoveryCode, HASH_OPTIONS);
      const passwordHash = await argon2.hash(newPassword, HASH_OPTIONS);
      await client.query(
        'UPDATE users SET password_hash = $1, recovery_code_hash = $2 WHERE id = $3',
        [passwordHash, recoveryHash, user.id],
      );
      await client.query('UPDATE sessions SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL', [user.id]);
      return { recoveryCode };
    });
    await this.clearAttempts('recover-account', key);
    return result;
  }
}
