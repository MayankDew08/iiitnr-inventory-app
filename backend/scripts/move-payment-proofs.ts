import 'dotenv/config';
import { copyFileSync, existsSync, mkdirSync, readdirSync } from 'node:fs';
import { basename, join } from 'node:path';
import { eq, isNotNull } from 'drizzle-orm';
import { db, pool } from '../src/drizzle/db.js';
import { payment } from '../src/drizzle/schema.js';

/**
 * One-off migration for the private-proof move (migration 0019 renames
 * `proofImageUrl` to `proofFileName`, but old rows still hold full
 * `/api/uploads/payments/<file>` URLs). Moves each referenced file from
 * `uploads/payments/` to `private-uploads/payments/` and rewrites the row to
 * the bare file name. Safe to re-run: rows that already hold a bare file name
 * are skipped, and a file that is already on disk in the target folder is
 * still recorded in the row.
 */
const OLD_DIR = join(process.cwd(), 'uploads', 'payments');
const NEW_DIR = join(process.cwd(), 'private-uploads', 'payments');

async function main() {
  mkdirSync(NEW_DIR, { recursive: true });

  const rows = await db
    .select({ id: payment.id, proofFileName: payment.proofFileName })
    .from(payment)
    .where(isNotNull(payment.proofFileName));

  let moved = 0;
  let alreadyBare = 0;
  let missing = 0;

  for (const row of rows) {
    const stored = row.proofFileName ?? '';
    const filename = basename(stored);
    if (!filename || stored === filename) {
      alreadyBare += 1;
      // A bare name whose file still sits in the old folder belongs in the
      // private folder now; move it so the proof route can find it.
      if (filename && existsSync(join(OLD_DIR, filename))) {
        copyFileSync(join(OLD_DIR, filename), join(NEW_DIR, filename));
        moved += 1;
      }
      continue;
    }

    const source = join(OLD_DIR, filename);
    if (!existsSync(source)) {
      missing += 1;
      console.warn(`  payment ${row.id}: source file missing (${stored}); row left untouched`);
      continue;
    }

    copyFileSync(source, join(NEW_DIR, filename));
    await db.update(payment).set({ proofFileName: filename }).where(eq(payment.id, row.id));
    moved += 1;
    console.log(`  payment ${row.id}: ${stored} -> ${filename}`);
  }

  // Move any leftover files the database no longer references, so a stray
  // proof never stays publicly servable under uploads/.
  const leftovers = existsSync(OLD_DIR)
    ? readdirSync(OLD_DIR).filter((name) => name !== '.gitkeep' && existsSync(join(OLD_DIR, name)))
    : [];
  for (const name of leftovers) {
    const target = join(NEW_DIR, name);
    if (!existsSync(target)) {
      copyFileSync(join(OLD_DIR, name), target);
    }
  }

  console.log(
    `Done. ${moved} file(s) moved, ${alreadyBare} row(s) already bare names, ${missing} missing.`,
  );
  console.log(
    `Old proofs stay in ${OLD_DIR} until deleted by hand — delete them after verifying, so nothing remains publicly served.`,
  );
}

main()
  .catch((e) => {
    console.error('❌ Error moving payment proofs:', e);
    process.exitCode = 1;
  })
  .finally(async () => {
    await pool.end();
  });
