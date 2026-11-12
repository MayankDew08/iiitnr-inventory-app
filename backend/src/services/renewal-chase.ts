import { and, eq, inArray, isNotNull } from 'drizzle-orm';
import { db } from '../drizzle/db.js';
import { request, requestEmail, user } from '../drizzle/schema.js';
import { RequestStatus } from '../utils/enums.js';
import { istDaysBetween } from './date-utils.js';
import { RequestEmailKind, sendTrackedEmail } from './email-service.js';
import { isMailEnabled } from './MailService.js';
import { buildEmailContext, renewalChaseEmail, RENEWAL_CHASE_LADDER } from './email-templates.js';
import { fetchExactAndShapeRequest } from './RequestService.js';
import { HOURLY_SWEEP_INTERVAL_MS, startIntervalSweep } from '../utils/sweep.js';

export interface RenewalChaseCandidate {
  requestId: string;
  recipientId: string;
  recipientEmail: string;
  daysSinceRequest: number;
  variant: string;
}

/**
 * Only the most relevant due rung is picked, so a request never produces more
 * than one chase email per sweep — and if the backend was down for a few days,
 * a single catch-up email is sent instead of one per missed rung.
 */
export async function findRenewalChaseCandidates(
  now: Date = new Date(),
): Promise<RenewalChaseCandidate[]> {
  const rows = await db
    .select({
      requestId: request.id,
      renewalRequestedAt: request.renewalRequestedAt,
      recipientId: user.id,
      recipientEmail: user.email,
    })
    .from(request)
    .innerJoin(user, eq(request.targetFacultyId, user.id))
    .where(
      and(eq(request.status, RequestStatus.REQUESTED_RENEW), isNotNull(request.renewalRequestedAt)),
    );

  if (!rows.length) return [];

  const sentRows = await db
    .select({ requestId: requestEmail.requestId, variant: requestEmail.variant })
    .from(requestEmail)
    .where(
      and(
        eq(requestEmail.kind, RequestEmailKind.RENEWAL_CHASE),
        inArray(
          requestEmail.requestId,
          rows.map((row) => row.requestId),
        ),
      ),
    );

  const alreadySent = new Set(sentRows.map((row) => `${row.requestId}:${row.variant}`));

  const candidates: RenewalChaseCandidate[] = [];
  for (const row of rows) {
    if (!row.renewalRequestedAt) continue;

    const daysSinceRequest = istDaysBetween(new Date(row.renewalRequestedAt), now);
    const due = RENEWAL_CHASE_LADDER.filter((rung) => daysSinceRequest >= rung.day)
      .filter((rung) => !alreadySent.has(`${row.requestId}:${rung.variant}`))
      .pop();
    if (!due) continue;

    candidates.push({
      requestId: row.requestId,
      recipientId: row.recipientId,
      recipientEmail: row.recipientEmail,
      daysSinceRequest,
      variant: due.variant,
    });
  }

  return candidates;
}

export async function sendRenewalChaseEmails(
  candidates: RenewalChaseCandidate[],
): Promise<{ sent: number; failed: number }> {
  let sent = 0;
  let failed = 0;

  for (const candidate of candidates) {
    const shaped = await fetchExactAndShapeRequest(candidate.requestId);
    if (!shaped) {
      failed++;
      continue;
    }

    const ctx = buildEmailContext(shaped);
    const { subject, text } = renewalChaseEmail(ctx, candidate.daysSinceRequest);

    const ok = await sendTrackedEmail({
      requestId: candidate.requestId,
      recipientId: candidate.recipientId,
      kind: RequestEmailKind.RENEWAL_CHASE,
      variant: candidate.variant,
      to: candidate.recipientEmail,
      subject,
      text,
      metadata: { daysSinceRequest: candidate.daysSinceRequest },
    });

    if (ok) sent++;
    else failed++;
  }

  return { sent, failed };
}

export async function runRenewalChaseSweep(
  now: Date = new Date(),
): Promise<{ candidates: number; sent: number; failed: number; skipped: boolean }> {
  if (!isMailEnabled()) {
    return { candidates: 0, sent: 0, failed: 0, skipped: true };
  }

  const candidates = await findRenewalChaseCandidates(now);
  const { sent, failed } = await sendRenewalChaseEmails(candidates);
  return { candidates: candidates.length, sent, failed, skipped: false };
}

export function startRenewalChaseSweep(
  onError: (err: unknown) => void,
  intervalMs: number = HOURLY_SWEEP_INTERVAL_MS,
) {
  return startIntervalSweep(
    async () => {
      await runRenewalChaseSweep();
    },
    onError,
    intervalMs,
  );
}
