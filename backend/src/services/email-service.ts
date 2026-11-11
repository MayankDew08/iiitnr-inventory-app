import { and, eq } from 'drizzle-orm';
import { db } from '../drizzle/db.js';
import { requestEmail } from '../drizzle/schema.js';
import { sendMail } from './MailService.js';

export const RequestEmailKind = {
  RENEWAL_CHASE: 'RENEWAL_CHASE',
  EXPIRY_NOTICE: 'EXPIRY_NOTICE',
  LATE_FEE: 'LATE_FEE',
  RENEWAL_REMINDER: 'RENEWAL_REMINDER',
} as const;

export type RequestEmailKindValue = (typeof RequestEmailKind)[keyof typeof RequestEmailKind];

type MailSender = (to: string, subject: string, text: string) => Promise<boolean>;

let mailSender: MailSender = sendMail;

export function setMailSender(sender: MailSender | null): void {
  mailSender = sender ?? sendMail;
}

export interface TrackedEmailInput {
  requestId: string;
  recipientId: string;
  kind: RequestEmailKindValue;
  variant: string;
  to: string;
  subject: string;
  text: string;
  metadata?: Record<string, unknown>;
}

const UNIQUE_VIOLATION = '23505';

function hasUniqueViolationCode(err: unknown): boolean {
  return (
    typeof err === 'object' &&
    err !== null &&
    'code' in err &&
    (err as { code?: unknown }).code === UNIQUE_VIOLATION
  );
}

function isUniqueViolation(err: unknown): boolean {
  let current: unknown = err;
  for (let depth = 0; depth < 5 && current; depth++) {
    if (hasUniqueViolationCode(current)) return true;
    current =
      typeof current === 'object' && current !== null
        ? (current as { cause?: unknown }).cause
        : undefined;
  }
  return false;
}

export async function hasSentEmail(
  requestId: string,
  recipientId: string,
  kind: RequestEmailKindValue,
  variant: string,
): Promise<boolean> {
  const [existing] = await db
    .select({ id: requestEmail.id })
    .from(requestEmail)
    .where(
      and(
        eq(requestEmail.requestId, requestId),
        eq(requestEmail.recipientId, recipientId),
        eq(requestEmail.kind, kind),
        eq(requestEmail.variant, variant),
      ),
    )
    .limit(1);

  return Boolean(existing);
}

async function claimEmail(input: TrackedEmailInput): Promise<boolean> {
  try {
    await db.insert(requestEmail).values({
      id: crypto.randomUUID(),
      requestId: input.requestId,
      recipientId: input.recipientId,
      kind: input.kind,
      variant: input.variant,
      subject: input.subject,
      metadata: input.metadata ?? null,
      sentAt: new Date().toISOString(),
    });
    return true;
  } catch (err) {
    if (isUniqueViolation(err)) return false;

    console.error('Failed to record sent email', {
      requestId: input.requestId,
      kind: input.kind,
      variant: input.variant,
      err,
    });
    return false;
  }
}

async function releaseEmail(input: TrackedEmailInput): Promise<void> {
  try {
    await db
      .delete(requestEmail)
      .where(
        and(
          eq(requestEmail.requestId, input.requestId),
          eq(requestEmail.recipientId, input.recipientId),
          eq(requestEmail.kind, input.kind),
          eq(requestEmail.variant, input.variant),
        ),
      );
  } catch (err) {
    console.error('Failed to release email claim', {
      requestId: input.requestId,
      kind: input.kind,
      variant: input.variant,
      err,
    });
  }
}

export async function sendTrackedEmail(input: TrackedEmailInput): Promise<boolean> {
  if (!(await claimEmail(input))) {
    return false;
  }

  const sent = await mailSender(input.to, input.subject, input.text);
  if (!sent) {
    await releaseEmail(input);
    return false;
  }

  return true;
}
