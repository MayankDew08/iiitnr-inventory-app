import 'dotenv/config';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Pool } from 'pg';
import { migrate } from 'drizzle-orm/node-postgres/migrator';
import { db, pool } from '../src/drizzle/db.js';

const url = process.env.TEST_DATABASE_URL ?? process.env.DATABASE_URL;
if (!url) {
  throw new Error('TEST_DATABASE_URL (or DATABASE_URL) must be set to prepare the test database');
}

async function waitForDb(maxAttempts = 30): Promise<void> {
  for (let i = 0; i < maxAttempts; i++) {
    const p = new Pool({ connectionString: url, connectionTimeoutMillis: 2000, max: 1 });
    try {
      await p.query('SELECT 1');
      await p.end();
      return;
    } catch (err) {
      console.warn(`⚠️ DB not ready (attempt ${i + 1}/${maxAttempts}): ${(err as Error).message}`);
      await p.end().catch(() => {});
      await new Promise((r) => setTimeout(r, 500));
    }
  }
  throw new Error('Database not ready after 15 seconds');
}

const __dirname = dirname(fileURLToPath(import.meta.url));
const migrationsFolder = join(__dirname, '..', 'src', 'drizzle');

await waitForDb();
await migrate(db, { migrationsFolder });
console.log('Migrations complete');

const truncatePool = new Pool({ connectionString: url });
try {
  await truncatePool.query(
    'TRUNCATE TABLE "User", "Request", "Component", "RequestItem", "AuditLog", "Notification", "RequestEmail" RESTART IDENTITY CASCADE',
  );
} finally {
  await truncatePool.end();
}
await pool.end();
console.log('Test database prepared (migrated + truncated)');
