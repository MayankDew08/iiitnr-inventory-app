import { mkdir } from 'node:fs/promises';

const UPLOADS = '/app/uploads/images';

await mkdir(UPLOADS, { recursive: true });

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
