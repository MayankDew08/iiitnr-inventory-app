import { mkdir, unlink, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const UPLOADS = '/app/uploads/images';
const PRIVATE_PROOFS = '/app/private-uploads/payments';

await mkdir(UPLOADS, { recursive: true });
await mkdir(PRIVATE_PROOFS, { recursive: true });

// Payment proofs cannot be reconstructed from the database, so fail at boot when
// the volume is missing or not writable by the container uid (65532) instead of
// failing after a student has already sent money.
const probe = join(PRIVATE_PROOFS, '.write-probe');
try {
  await writeFile(probe, 'ok');
  await unlink(probe);
} catch (err) {
  console.error(`[entrypoint] ${PRIVATE_PROOFS} is not writable: ${String(err)}`);
  console.error('[entrypoint] mount ./private-uploads and chown it to 65532:65532');
  process.exit(1);
}

async function run(label: string, args: string[]): Promise<void> {
  console.log(`[entrypoint] ${label}...`);
  const proc = Bun.spawn(['bun', ...args], {
    stdout: 'inherit',
    stderr: 'inherit',
    stdin: 'inherit',
  });
  const code = await proc.exited;
  if (code !== 0) {
    console.error(`[entrypoint] ${label} failed (exit ${code})`);
    process.exit(code);
  }
}

await run('migrations', ['run', 'src/index.ts']);

const skipSeed = ['1', 'true', 'yes'].includes((process.env.SKIP_SEED ?? '').toLowerCase());
if (!skipSeed) await run('seed', ['run', 'scripts/seed.ts']);

const server = Bun.spawn(['bun', 'run', 'src/server.ts'], {
  stdio: ['inherit', 'inherit', 'inherit'],
});

for (const sig of ['SIGTERM', 'SIGINT', 'SIGHUP'] as const) {
  process.on(sig, () => server.kill(sig));
}

process.exit(await server.exited);
