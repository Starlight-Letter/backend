import { Pool } from 'pg';
import { PostgresAuthService } from '../domains/auth';

const { DATABASE_URL, ADMIN_TOTP_KEY, ADMIN_NICKNAME, ADMIN_PASSWORD } = process.env;
const reset = process.argv[2] === 'reset';
if (!DATABASE_URL || !ADMIN_TOTP_KEY || !ADMIN_NICKNAME || (!reset && !ADMIN_PASSWORD)) {
  throw new Error('DATABASE_URL, ADMIN_TOTP_KEY, ADMIN_NICKNAME and (for provisioning) ADMIN_PASSWORD are required');
}

const key = Buffer.from(ADMIN_TOTP_KEY, 'base64');
if (key.length !== 32 || key.toString('base64') !== ADMIN_TOTP_KEY) {
  throw new Error('ADMIN_TOTP_KEY must be a base64-encoded 32-byte key');
}

const pool = new Pool({ connectionString: DATABASE_URL, max: 1 });
try {
  const auth = new PostgresAuthService(pool, key);
  const { totpSecret } = reset
    ? await auth.resetAdminTotp(ADMIN_NICKNAME)
    : await auth.provisionAdmin(ADMIN_NICKNAME, ADMIN_PASSWORD);
  process.stdout.write(`TOTP secret (save once): ${totpSecret}\n`);
} finally {
  await pool.end();
}
