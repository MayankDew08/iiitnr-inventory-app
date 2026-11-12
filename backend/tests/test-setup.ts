import 'dotenv/config';
import { execSync } from 'node:child_process';

process.env.NODE_ENV = 'test';
process.env.JWT_SECRET = process.env.JWT_SECRET ?? 'test-secret';

if (!process.env.TEST_DATABASE_URL && process.env.DATABASE_URL) {
  const mainDbUrl = process.env.DATABASE_URL;
  try {
    const url = new URL(mainDbUrl);
    const dbName = url.pathname.slice(1);
    url.pathname = `/${dbName}_test`;
    process.env.TEST_DATABASE_URL = url.toString();
    console.warn(
      '⚠️ TEST_DATABASE_URL not set. Using derived test database:',
      process.env.TEST_DATABASE_URL,
    );
    console.warn('⚠️ Please set TEST_DATABASE_URL explicitly in your .env file for safety.');
  } catch (err) {
    console.error('Failed to derive TEST_DATABASE_URL from DATABASE_URL:', err);
    throw new Error(
      'TEST_DATABASE_URL must be set explicitly when DATABASE_URL format is not standard',
    );
  }
}

if (process.env.TEST_DATABASE_URL && process.env.DATABASE_URL) {
  const testDb = process.env.TEST_DATABASE_URL.toLowerCase();
  const prodDb = process.env.DATABASE_URL.toLowerCase();

  if (testDb === prodDb) {
    console.error('❌ ERROR: TEST_DATABASE_URL cannot be the same as DATABASE_URL!');
    console.error('❌ This will delete production data!');
    throw new Error('TEST_DATABASE_URL must be different from DATABASE_URL');
  }

  if (!testDb.includes('_test')) {
    console.warn(
      '⚠️ WARNING: TEST_DATABASE_URL does not contain "_test" - are you sure this is a test database?',
    );
  }
}

const dbUrlForMigrations = process.env.TEST_DATABASE_URL ?? process.env.DATABASE_URL;

if (!dbUrlForMigrations) {
  throw new Error(
    '❌ TEST_DATABASE_URL (or DATABASE_URL for derivation) must be set to run tests.',
  );
}

try {
  execSync('bun run tests/db-prepare.ts', {
    stdio: 'inherit',
    env: {
      ...process.env,
      NODE_ENV: 'test',
      DATABASE_URL: dbUrlForMigrations,
      TEST_DATABASE_URL: dbUrlForMigrations,
    },
    timeout: 120_000,
  });
} catch (err) {
  console.error('❌ Failed to prepare the test database.', err);
  throw err;
}
