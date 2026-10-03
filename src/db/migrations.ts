import { createHash } from 'node:crypto';
import { readFile, readdir } from 'node:fs/promises';
import { join } from 'node:path';
import type { PoolClient } from 'pg';

export async function applyMigrations(client: PoolClient, directory: string): Promise<void> {
  const files = (await readdir(directory)).filter((file) => /^\d+_[\w-]+\.sql$/.test(file)).sort();
  await client.query('BEGIN');
  try {
    await client.query(`CREATE TABLE IF NOT EXISTS schema_migrations (
      filename text PRIMARY KEY,
      checksum text NOT NULL,
      applied_at timestamptz NOT NULL DEFAULT now()
    )`);
    for (const filename of files) {
      const sql = await readFile(join(directory, filename), 'utf8');
      const checksum = createHash('sha256').update(sql).digest('hex');
      const existing = await client.query<{ checksum: string }>(
        'SELECT checksum FROM schema_migrations WHERE filename = $1', [filename],
      );
      if (existing.rows[0]) {
        if (existing.rows[0].checksum !== checksum) throw new Error(`Migration changed: ${filename}`);
        continue;
      }
      await client.query(sql);
      await client.query('INSERT INTO schema_migrations (filename, checksum) VALUES ($1, $2)', [filename, checksum]);
    }
    await client.query('COMMIT');
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  }
}
