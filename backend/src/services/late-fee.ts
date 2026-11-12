import { and, eq, inArray, isNotNull, isNull } from 'drizzle-orm';
import { db } from '../drizzle/db.js';
import { request, requestEmail, user } from '../drizzle/schema.js';
import { RequestStatus } from '../utils/enums.js';
import { istDaysBetween } from './date-utils.js';
import { RequestEmailKind, sendTrackedEmail } from './email-service.js';
import { isMailEnabled } from './MailService.js';
import {
  buildEmailContext,
  lateFeeEmail,
  LATE_FEE_CAP_DAYS,
  LATE_FEE_LADDER,
  LATE_FEE_PER_DAY,
} from './email-templates.js';
import { fetchExactAndShapeRequest } from './RequestService.js';
import { HOURLY_SWEEP_INTERVAL_MS, startIntervalSweep } from '../utils/sweep.js';

export interface LateFeeCandidate {
  requestId: string;
  recipientId: string;
  recipientEmail: string;
  daysOverdue: number;
  feeAccrued: number;
  variant: string;
}

export function computeLateFee(daysOverdue: number): number {
  if (daysOverdue <= 0) return 0;
  return Math.min(daysOverdue, LATE_FEE_CAP_DAYS) * LATE_FEE_PER_DAY;
}

/**
 * `returnedAt` is only set once everything is back, so a partially returned
 * request that later expired is still charged for what is outstanding.
 */
export async function findLateFeeCandidates(now: Date = new Date()): Promise<LateFeeCandidate[]> {
  const rows = await db
    .select({
      requestId: request.id,
      returnDueAt: request.returnDueAt,
      recipientId: user.id,
      recipientEmail: user.email,
    })
    .from(request)
    .innerJoin(user, eq(request.userId, user.id))
    .where(
      and(
        eq(request.status, RequestStatus.EXPIRED),
        isNull(request.returnedAt),
        isNotNull(request.returnDueAt),
      ),
    );

  if (!rows.length) return [];

  const sentRows = await db
    .select({ requestId: requestEmail.requestId, variant: requestEmail.variant })
    .from(requestEmail)
    .where(
      and(
        eq(requestEmail.kind, RequestEmailKind.LATE_FEE),
        inArray(
          requestEmail.requestId,
          rows.map((row) => row.requestId),
        ),
      ),
    );

  const alreadySent = new Set(sentRows.map((row) => `${row.requestId}:${row.variant}`));

  const candidates: LateFeeCandidate[] = [];
  for (const row of rows) {
    if (!row.returnDueAt) continue;

    const daysOverdue = istDaysBetween(new Date(row.returnDueAt), now);
    if (daysOverdue < 1) continue;

    const due = LATE_FEE_LADDER.filter((rung) => daysOverdue >= rung.day)
      .filter((rung) => !alreadySent.has(`${row.requestId}:${rung.variant}`))
      .pop();
    if (!due) continue;

    candidates.push({
      requestId: row.requestId,
      recipientId: row.recipientId,
      recipientEmail: row.recipientEmail,
      daysOverdue,
      feeAccrued: computeLateFee(daysOverdue),
      variant: due.variant,
    });
  }

  return candidates;
}

/** Keeps `Request.lateFeeAccrued` in step with the overdue period. */
export async function syncAccruedFees(now: Date = new Date()): Promise<number> {
  const rows = await db
    .select({
      requestId: request.id,
      returnDueAt: request.returnDueAt,
      accrued: request.lateFeeAccrued,
    })
    .from(request)
    .where(
      and(
        eq(request.status, RequestStatus.EXPIRED),
        isNull(request.returnedAt),
        isNotNull(request.returnDueAt),
      ),
    );

  let updated = 0;
  for (const row of rows) {
    if (!row.returnDueAt) continue;
    const fee = computeLateFee(istDaysBetween(new Date(row.returnDueAt), now));
    if (fee === row.accrued) continue;

    await db
      .update(request)
      .set({ lateFeeAccrued: fee, updatedAt: now.toISOString() })
      .where(eq(request.id, row.requestId));
    updated++;
  }

  return updated;
}

export async function runLateFeeSweep(now: Date = new Date()): Promise<{
  candidates: number;
  sent: number;
  failed: number;
  feesUpdated: number;
  skipped: boolean;
}> {
  // The charge is a record and keeps accruing either way — only the mails
  // are optional.
  const feesUpdated = await syncAccruedFees(now);

  if (!isMailEnabled()) {
    return { candidates: 0, sent: 0, failed: 0, feesUpdated, skipped: true };
  }

  const candidates = await findLateFeeCandidates(now);

  let sent = 0;
  let failed = 0;

  for (const candidate of candidates) {
    const shaped = await fetchExactAndShapeRequest(candidate.requestId);
    if (!shaped) {
      failed++;
      continue;
    }

    const ctx = buildEmailContext(shaped);
    const { subject, text } = lateFeeEmail(
      ctx,
      candidate.daysOverdue,
      candidate.feeAccrued,
      candidate.variant,
    );

    const ok = await sendTrackedEmail({
      requestId: candidate.requestId,
      recipientId: candidate.recipientId,
      kind: RequestEmailKind.LATE_FEE,
      variant: candidate.variant,
      to: candidate.recipientEmail,
      subject,
      text,
      metadata: {
        daysOverdue: candidate.daysOverdue,
        feeAccrued: candidate.feeAccrued,
      },
    });

    if (ok) sent++;
    else failed++;
  }

  return { candidates: candidates.length, sent, failed, feesUpdated, skipped: false };
}

export function startLateFeeSweep(
  onError: (err: unknown) => void,
  intervalMs: number = HOURLY_SWEEP_INTERVAL_MS,
) {
  return startIntervalSweep(
    async () => {
      await runLateFeeSweep();
    },
    onError,
    intervalMs,
  );
}
