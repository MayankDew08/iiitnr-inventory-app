import { buildApp, markAppReady } from './app.js';
import { pool } from './drizzle/db.js';

const app = await buildApp();
const port = Number(process.env.PORT ?? 4000);
const host = process.env.HOST ?? (process.env.NODE_ENV === 'production' ? '0.0.0.0' : '127.0.0.1');

try {
  await pool.query('SELECT 1');
  app.log.info('Database connection OK');
} catch {
  throw new Error('DB connection failed, check your connection string');
}

try {
  const res = await pool.query('SELECT 1 AS n FROM drizzle.__drizzle_migrations LIMIT 1');
  if (!res.rows?.length) {
    throw new Error('No migrations applied. Run: bun run src/index.ts');
  }
  app.log.info('Migrations applied');
} catch {
  throw new Error('Migrations not applied or migration table missing. Run: bun run src/index.ts');
}

try {
  await app.listen({ port, host });
  markAppReady();
  app.log.info(`Server listening on http://${host}:${port}`);
} catch (err) {
  throw new Error(`Failed to start server: ${err}`, { cause: err });
}

const signals = ['SIGINT', 'SIGTERM'];
for (const signal of signals) {
  process.on(signal, () => {
    void (async () => {
      app.log.info(`${signal} received, shutting down gracefully...`);
      try {
        await app.close();
        app.log.info('Server closed successfully.');
        process.exit(0);
      } catch (err) {
        throw new Error(`${err} Error during graceful shutdown`, { cause: err });
      }
    })().catch((err) => {
      app.log.error(err, 'Error during graceful shutdown');
    });
  });
}
