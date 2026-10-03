import { fileURLToPath } from 'node:url';
import { Pool } from 'pg';
import { applyMigrations } from './migrations';

if (!process.env.DATABASE_URL) throw new Error('DATABASE_URL is required');

const pool = new Pool({ connectionString: process.env.DATABASE_URL, max: 1 });
const directory = fileURLToPath(new URL('../../migrations/', import.meta.url));
const client = await pool.connect();
try {
  await applyMigrations(client, directory);
  process.stdout.write('Migrations applied\n');
} finally {
  client.release();
  await pool.end();
}
