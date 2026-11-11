import { and, eq, gte, inArray, isNotNull, isNull, lt } from 'drizzle-orm';
import { db } from '../drizzle/db.js';
import { request, user } from '../drizzle/schema.js';
import { ACTIVE_LOAN_STATUSES } from './request-expiry.js';
import { isMailEnabled } from './MailService.js';
import { hasSentEmail, RequestEmailKind, sendTrackedEmail } from './email-service.js';
import { buildEmailContext, renewalReminderEmail } from './email-templates.js';
import { IST_DAY_MS, istDayStart } from './date-utils.js';
import { fetchExactAndShapeRequest } from './RequestService.js';
import { HOURLY_SWEEP_INTERVAL_MS, startIntervalSweep } from '../utils/sweep.js';

const RENEWAL_REMINDER_DAYS = 7;
const RENEWAL_REMINDER_VARIANT = 'D7';

export interface RenewalReminderCandidate {
  requestId: string;
  userId: string;
  userEmail: string;
  projectTitle: string;
  returnDueAt: string;
}

export async function findRenewalReminderCandidates(
  now: Date = new Date(),
): Promise<RenewalReminderCandidate[]> {
  const windowEnd = new Date(istDayStart(now).getTime() + (RENEWAL_REMINDER_DAYS + 1) * IST_DAY_MS);

  const rows = await db
    .select({
      requestId: request.id,
      userId: request.userId,
      userEmail: user.email,
      projectTitle: request.projectTitle,
      returnDueAt: request.returnDueAt,
    })
    .from(request)
    .innerJoin(user, eq(request.userId, user.id))
    .where(
      and(
        isNotNull(request.returnDueAt),
        inArray(request.status, ACTIVE_LOAN_STATUSES),
        isNull(request.renewalReminderSentAt),
        gte(request.returnDueAt, now.toISOString()),
        lt(request.returnDueAt, windowEnd.toISOString()),
      ),
    );

  return rows.filter((row): row is RenewalReminderCandidate => row.returnDueAt !== null);
}

function reminderVariant(returnDueAt: string): string {
  return `${RENEWAL_REMINDER_VARIANT}:${returnDueAt.slice(0, 10)}`;
}

export async function sendRenewalReminders(
  candidates: RenewalReminderCandidate[],
): Promise<{ sent: number; failed: number }> {
  let sent = 0;
  let failed = 0;

  for (const c of candidates) {
    const shaped = await fetchExactAndShapeRequest(c.requestId);
    if (!shaped) {
      failed++;
      continue;
    }

    const { subject, text } = renewalReminderEmail(buildEmailContext(shaped));
    const variant = reminderVariant(c.returnDueAt);

    const ok = await sendTrackedEmail({
      requestId: c.requestId,
      recipientId: c.userId,
      kind: RequestEmailKind.RENEWAL_REMINDER,
      variant,
      to: c.userEmail,
      subject,
      text,
    });

    const alreadySent =
      !ok &&
      (await hasSentEmail(c.requestId, c.userId, RequestEmailKind.RENEWAL_REMINDER, variant));

    if (ok || alreadySent) {
      sent++;
      await markReminderSent(c.requestId);
    } else {
      failed++;
    }
  }

  return { sent, failed };
}

async function markReminderSent(requestId: string): Promise<void> {
  const nowIso = new Date().toISOString();
  await db
    .update(request)
    .set({ renewalReminderSentAt: nowIso, updatedAt: nowIso })
    .where(eq(request.id, requestId));
}

export async function runRenewalReminderSweep(): Promise<{
  candidates: number;
  sent: number;
  failed: number;
  skipped: boolean;
}> {
  if (!isMailEnabled()) {
    return { candidates: 0, sent: 0, failed: 0, skipped: true };
  }

  const candidates = await findRenewalReminderCandidates();
  const { sent, failed } = await sendRenewalReminders(candidates);
  return { candidates: candidates.length, sent, failed, skipped: false };
}

export function startRenewalReminderSweep(
  onError: (err: unknown) => void,
  intervalMs: number = HOURLY_SWEEP_INTERVAL_MS,
) {
  return startIntervalSweep(
    async () => {
      const result = await runRenewalReminderSweep();
      if (result.candidates > 0) {
        console.log('Renewal reminder sweep', result);
      }
    },
    onError,
    intervalMs,
  );
}
