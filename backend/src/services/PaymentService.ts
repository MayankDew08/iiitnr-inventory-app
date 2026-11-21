import { and, asc, desc, eq, gt, inArray, isNotNull, isNull } from 'drizzle-orm';
import { db } from '../drizzle/db.js';
import { payment, paymentAllocation, request, user } from '../drizzle/schema.js';
import { logAudit } from '../utils/audit.js';
import { AuditActionType, paymentRejectionReasonValues } from '../utils/enums.js';
import type { PaymentRejectionReasonValue } from '../utils/enums.js';
import { DomainError, ForbiddenError, NotFoundError } from '../utils/errors.js';
import { createNotification, NotificationType } from './NotificationService.js';

export type PaymentRecord = typeof payment.$inferSelect;
export type PaymentAllocationRecord = typeof paymentAllocation.$inferSelect;

export type PaymentWithAllocations = PaymentRecord & {
  allocations: PaymentAllocationRecord[];
};

/** Summary row for the LA's pending-verification queue. */
export interface PendingPaymentSummary {
  id: string;
  userId: string;
  studentName: string | null;
  studentEmail: string;
  amount: number;
  utr: string | null;
  submittedAt: string | null;
}

/** Everything an LA needs to judge a submitted payment. */
export interface PaymentAdminDetail {
  payment: PaymentRecord;
  student: { id: string; name: string | null; email: string };
  allocations: Array<{
    requestId: string;
    amount: number;
    projectTitle: string;
    returnDueAt: string | null;
    returnedAt: string | null;
    items: Array<{ componentName: string; quantity: number }>;
  }>;
}

const PAYMENT_REJECTION_LABELS: Record<PaymentRejectionReasonValue, string> = {
  UTR_NOT_FOUND: 'UTR was not found in bank records',
  AMOUNT_MISMATCH: 'amount received did not match',
  PROOF_UNCLEAR: 'payment proof was unclear',
  DUPLICATE_PAYMENT: 'this payment was already verified',
  OTHER: 'see notes',
};

// The "delete the dead PENDING" branch must commit before the 400 is raised;
// throwing inside the transaction would roll the delete back.
type CreateIntentResult =
  { ok: true; payment: PaymentWithAllocations } | { ok: false; error: DomainError };

const UNIQUE_VIOLATION = '23505';

function isUniqueViolationOnConstraint(err: unknown, constraint: string): boolean {
  let current: unknown = err;
  for (let depth = 0; depth < 5 && current; depth++) {
    if (
      typeof current === 'object' &&
      current !== null &&
      (current as { code?: unknown }).code === UNIQUE_VIOLATION &&
      (current as { constraint?: unknown }).constraint === constraint
    ) {
      return true;
    }
    current =
      typeof current === 'object' && current !== null
        ? (current as { cause?: unknown }).cause
        : undefined;
  }
  return false;
}

/**
 * Public shape of a payment: `proofFileName` (a bare file name on disk) is
 * never exposed; the app only ever sees `proofImageUrl` pointing at the
 * authenticated `/payments/{id}/proof` route (Caddy adds the `/api` prefix
 * in production; the app's ApiClient prepends its base URL in dev), or null
 * when there is no proof on disk.
 */
export type PaymentDto = Omit<PaymentRecord, 'proofFileName'> & {
  proofImageUrl: string | null;
};

export function toPaymentDto<T extends PaymentRecord>(
  record: T,
): Omit<T, 'proofFileName'> & {
  proofImageUrl: string | null;
} {
  const { proofFileName, ...rest } = record;
  return {
    ...rest,
    proofImageUrl: proofFileName ? `/payments/${record.id}/proof` : null,
  };
}

/** The student's one active (PENDING/SUBMITTED) payment, if any, with its allocations. */
async function findActivePayment(userId: string): Promise<PaymentWithAllocations | null> {
  const [row] = await db
    .select()
    .from(payment)
    .where(and(eq(payment.userId, userId), inArray(payment.status, ['PENDING', 'SUBMITTED'])))
    .limit(1);
  if (!row) {
    return null;
  }
  const allocations = await db
    .select()
    .from(paymentAllocation)
    .where(eq(paymentAllocation.paymentId, row.id));
  return { ...row, allocations };
}

/** A single payment row by id, or null when there is no such payment. */
export async function getPaymentById(paymentId: string): Promise<PaymentRecord | null> {
  const [row] = await db.select().from(payment).where(eq(payment.id, paymentId)).limit(1);
  return row ?? null;
}

/**
 * Returns the student's PENDING payment, refreshed against the current set of
 * unpaid finalized fines, or creates one covering them. The amount is always
 * computed here from the database; any amount supplied by a client is ignored.
 */
export async function createPaymentIntent(userId: string): Promise<PaymentWithAllocations> {
  let result: CreateIntentResult;
  try {
    result = await runCreateIntent(userId);
  } catch (err) {
    // B2: two concurrent first-time calls can both miss the PENDING lookup
    // (FOR UPDATE has no row to lock) and race to insert. The partial unique
    // index Payment_one_active_per_user lets exactly one of them win; the
    // loser re-reads the winner's row instead of failing.
    if (isUniqueViolationOnConstraint(err, 'Payment_one_active_per_user')) {
      const active = await findActivePayment(userId);
      if (active) {
        if (active.status === 'SUBMITTED') {
          throw new DomainError(
            409,
            'payment_awaiting_verification',
            'PAYMENT_AWAITING_VERIFICATION',
          );
        }
        return active;
      }
    }
    throw err;
  }
  if (!result.ok) {
    throw result.error;
  }
  return result.payment;
}

async function runCreateIntent(userId: string): Promise<CreateIntentResult> {
  return db.transaction(async (tx): Promise<CreateIntentResult> => {
    // B1: one active payment per student. Active means PENDING or SUBMITTED;
    // a SUBMITTED payment means money is already in flight, so the student
    // must not start a second one. Return (not throw) so the transaction
    // commits and nothing rolls back by accident.
    const [submitted] = await tx
      .select()
      .from(payment)
      .where(and(eq(payment.userId, userId), eq(payment.status, 'SUBMITTED')))
      .limit(1);
    if (submitted) {
      return {
        ok: false,
        error: new DomainError(
          409,
          'payment_awaiting_verification',
          'PAYMENT_AWAITING_VERIFICATION',
        ),
      };
    }
    const [existing] = await tx
      .select()
      .from(payment)
      .where(and(eq(payment.userId, userId), eq(payment.status, 'PENDING')))
      .limit(1)
      .for('update');

    // Lock the fines so a concurrent return cannot change lateFeeAccrued
    // between this read and the allocation insert/refresh.
    const fines = await tx
      .select({ id: request.id, lateFeeAccrued: request.lateFeeAccrued })
      .from(request)
      .where(
        and(
          eq(request.userId, userId),
          isNotNull(request.returnedAt),
          gt(request.lateFeeAccrued, 0),
          isNull(request.fineSettledAt),
        ),
      )
      .for('update');

    if (fines.length === 0) {
      // A PENDING that covers nothing is a dead intent. Delete it so the
      // student is back to "no payment in progress" and can retry cleanly.
      if (existing) {
        await tx.delete(payment).where(eq(payment.id, existing.id));
      }
      return { ok: false, error: new DomainError(400, 'no unpaid fines', 'NO_UNPAID_FINES') };
    }

    const now = new Date().toISOString();

    if (existing) {
      const currentIds = (
        await tx
          .select({ requestId: paymentAllocation.requestId })
          .from(paymentAllocation)
          .where(eq(paymentAllocation.paymentId, existing.id))
      )
        .map((allocation) => allocation.requestId)
        .sort();
      const fineIds = fines.map((fine) => fine.id).sort();
      const unchanged =
        currentIds.length === fineIds.length &&
        currentIds.every((id, index) => id === fineIds[index]);

      if (unchanged) {
        const allocations = await tx
          .select()
          .from(paymentAllocation)
          .where(eq(paymentAllocation.paymentId, existing.id));

        return { ok: true, payment: { ...existing, allocations } };
      }

      // New fines were finalized (or a previously-allocated one dropped out):
      // rebuild the allocations and the amount on the same PENDING row.
      await tx.delete(paymentAllocation).where(eq(paymentAllocation.paymentId, existing.id));

      const total = fines.reduce((sum, fine) => sum + fine.lateFeeAccrued, 0);
      const [updated] = await tx
        .update(payment)
        .set({ amount: total })
        .where(eq(payment.id, existing.id))
        .returning();

      if (!updated) {
        throw new Error('failed to refresh payment');
      }

      const allocations = await tx
        .insert(paymentAllocation)
        .values(
          fines.map((fine) => ({
            id: crypto.randomUUID(),
            paymentId: existing.id,
            requestId: fine.id,
            amount: fine.lateFeeAccrued,
            createdAt: now,
          })),
        )
        .returning();

      return { ok: true, payment: { ...updated, allocations } };
    }

    const total = fines.reduce((sum, fine) => sum + fine.lateFeeAccrued, 0);
    const paymentId = crypto.randomUUID();

    const [created] = await tx
      .insert(payment)
      .values({
        id: paymentId,
        userId,
        amount: total,
        status: 'PENDING',
        createdAt: now,
      })
      .returning();

    if (!created) {
      throw new Error('failed to create payment');
    }

    const allocations = await tx
      .insert(paymentAllocation)
      .values(
        fines.map((fine) => ({
          id: crypto.randomUUID(),
          paymentId,
          requestId: fine.id,
          amount: fine.lateFeeAccrued,
          createdAt: now,
        })),
      )
      .returning();

    return { ok: true, payment: { ...created, allocations } };
  });
}

/**
 * Lists the student's non-verified payments, newest first, with allocations
 * loaded in one extra query so the list never does an N+1 lookup.
 */
export async function listMyPayments(userId: string): Promise<PaymentWithAllocations[]> {
  const rows = await db
    .select()
    .from(payment)
    .where(
      and(
        eq(payment.userId, userId),
        inArray(payment.status, ['PENDING', 'SUBMITTED', 'REJECTED']),
      ),
    )
    .orderBy(desc(payment.createdAt));

  if (rows.length === 0) {
    return [];
  }

  const allocations = await db
    .select()
    .from(paymentAllocation)
    .where(
      inArray(
        paymentAllocation.paymentId,
        rows.map((row) => row.id),
      ),
    );

  const byPaymentId = new Map<string, PaymentAllocationRecord[]>();
  for (const allocation of allocations) {
    const group = byPaymentId.get(allocation.paymentId) ?? [];
    group.push(allocation);
    byPaymentId.set(allocation.paymentId, group);
  }

  return rows.map((row) => ({ ...row, allocations: byPaymentId.get(row.id) ?? [] }));
}

/**
 * Stamps the UTR and proof on a PENDING payment. A payment is immutable once
 * submitted: only PENDING → SUBMITTED is allowed. `proofFileName` is only the
 * file name on disk; API responses map it to `proofImageUrl` (see
 * toPaymentDto). The UTR must already be normalized to 12 digits by the caller.
 */
export function submitPayment(
  paymentId: string,
  userId: string,
  utr: string,
  proofFileName: string,
): Promise<PaymentRecord> {
  return db.transaction(async (tx) => {
    const [locked] = await tx.select().from(payment).where(eq(payment.id, paymentId)).for('update');

    if (!locked) {
      throw new NotFoundError('Payment', paymentId);
    }

    // Check ownership before status so probing another student's id does not
    // reveal whether it is pending, submitted, or verified.
    if (locked.userId !== userId) {
      throw new ForbiddenError('forbidden: payment belongs to another user');
    }

    if (locked.status !== 'PENDING') {
      throw new DomainError(409, 'payment_not_pending', 'PAYMENT_NOT_PENDING');
    }

    // A UTR rejected as not-found or duplicate stays blocked forever. One
    // rejected as PROOF_UNCLEAR or OTHER may be retried (the student
    // re-uploads a clearer screenshot of the same payment).
    const [blocked] = await tx
      .select({ id: payment.id })
      .from(payment)
      .where(
        and(
          eq(payment.utr, utr),
          eq(payment.status, 'REJECTED'),
          inArray(payment.rejectionReasonCode, ['UTR_NOT_FOUND', 'DUPLICATE_PAYMENT']),
        ),
      )
      .limit(1);
    if (blocked) {
      throw new DomainError(409, 'utr_blocked', 'UTR_BLOCKED');
    }

    let updated: PaymentRecord | undefined;
    try {
      [updated] = await tx
        .update(payment)
        .set({
          status: 'SUBMITTED',
          utr,
          proofFileName,
          submittedAt: new Date().toISOString(),
        })
        .where(eq(payment.id, paymentId))
        .returning();
    } catch (err) {
      if (isUniqueViolationOnConstraint(err, 'Payment_utr_active_key')) {
        throw new DomainError(409, 'utr_already_used', 'UTR_ALREADY_USED');
      }
      throw err;
    }

    if (!updated) {
      throw new Error('failed to submit payment');
    }

    return updated;
  });
}

/**
 * Pending-verification queue for LA/admin. Oldest submission first so the
 * queue is fair. Proof and allocations are intentionally omitted; the LA
 * loads those on the detail endpoint.
 */
export function listPendingForAdmin(): Promise<PendingPaymentSummary[]> {
  return db
    .select({
      id: payment.id,
      userId: payment.userId,
      studentName: user.name,
      studentEmail: user.email,
      amount: payment.amount,
      utr: payment.utr,
      submittedAt: payment.submittedAt,
    })
    .from(payment)
    .innerJoin(user, eq(payment.userId, user.id))
    .where(eq(payment.status, 'SUBMITTED'))
    .orderBy(asc(payment.submittedAt));
}

/** Payment, student and allocated fines with their request items. */
export async function getPaymentForAdmin(paymentId: string): Promise<PaymentAdminDetail> {
  const [row] = await db.select().from(payment).where(eq(payment.id, paymentId)).limit(1);
  if (!row) {
    throw new NotFoundError('Payment', paymentId);
  }

  const [student] = await db
    .select({ id: user.id, name: user.name, email: user.email })
    .from(user)
    .where(eq(user.id, row.userId))
    .limit(1);

  const allocations = await db
    .select()
    .from(paymentAllocation)
    .where(eq(paymentAllocation.paymentId, paymentId));

  const requestIds = allocations.map((allocation) => allocation.requestId);
  const requests = requestIds.length
    ? await db.query.request.findMany({
        where: inArray(request.id, requestIds),
        with: { requestItems: { with: { component: true } } },
      })
    : [];
  const requestsById = new Map(requests.map((linkedRequest) => [linkedRequest.id, linkedRequest]));

  return {
    payment: row,
    student: student ?? { id: row.userId, name: null, email: '' },
    allocations: allocations.map((allocation) => {
      const linkedRequest = requestsById.get(allocation.requestId);
      return {
        requestId: allocation.requestId,
        amount: allocation.amount,
        projectTitle: linkedRequest?.projectTitle ?? '',
        returnDueAt: linkedRequest?.returnDueAt ?? null,
        returnedAt: linkedRequest?.returnedAt ?? null,
        items:
          linkedRequest?.requestItems.map((item) => ({
            componentName: item.component?.name ?? 'Unknown component',
            quantity: item.quantity,
          })) ?? [],
      };
    }),
  };
}

/**
 * Verifies a submitted payment and settles every allocated fine in one
 * transaction. The audit log and notification are written after commit
 * because both helpers use the global db and swallow their own errors.
 */
export async function verifyPayment(
  paymentId: string,
  adminUserId: string,
): Promise<PaymentRecord> {
  const updated = await db.transaction(async (tx) => {
    const [locked] = await tx
      .select()
      .from(payment)
      .where(eq(payment.id, paymentId))
      .limit(1)
      .for('update');

    if (!locked) {
      throw new NotFoundError('Payment', paymentId);
    }
    if (locked.status !== 'SUBMITTED') {
      throw new DomainError(409, 'payment_not_submitted', 'PAYMENT_NOT_SUBMITTED');
    }

    const allocations = await tx
      .select()
      .from(paymentAllocation)
      .where(eq(paymentAllocation.paymentId, paymentId));

    // Lock allocated requests in a stable order so concurrent verifies and
    // return transactions cannot deadlock each other.
    const requestIds = allocations.map((allocation) => allocation.requestId).sort();
    let alreadySettled = false;
    if (requestIds.length > 0) {
      const lockedRequests = await tx
        .select({ id: request.id, fineSettledAt: request.fineSettledAt })
        .from(request)
        .where(inArray(request.id, requestIds))
        .orderBy(request.id)
        .for('update');
      alreadySettled = lockedRequests.some((row) => row.fineSettledAt != null);
    }

    // B1 backup behind the one-active-per-student index: settling an
    // already-settled fine would double-count it.
    if (alreadySettled) {
      throw new DomainError(409, 'fine_already_settled', 'FINE_ALREADY_SETTLED');
    }

    const now = new Date().toISOString();

    if (requestIds.length > 0) {
      await tx
        .update(request)
        .set({ fineSettledAt: now, updatedAt: now })
        .where(inArray(request.id, requestIds));
    }

    const [paymentUpdated] = await tx
      .update(payment)
      .set({ status: 'VERIFIED', verifiedAt: now, verifiedByUserId: adminUserId })
      .where(eq(payment.id, paymentId))
      .returning();

    if (!paymentUpdated) {
      throw new Error('failed to verify payment');
    }

    return paymentUpdated;
  });

  await logAudit({
    userId: adminUserId,
    action: AuditActionType.UPDATE,
    entityType: 'Payment',
    entityId: updated.id,
    oldValues: { status: 'SUBMITTED' },
    newValues: { status: 'VERIFIED', verifiedByUserId: adminUserId },
  });

  await createNotification({
    recipientId: updated.userId,
    type: NotificationType.PAYMENT_VERIFIED,
    title: 'Payment verified',
    body: `Your payment of ₹${updated.amount} has been verified. Your fines are settled.`,
  });

  return updated;
}

/**
 * Rejects a submitted payment without touching its allocated fines, so the
 * student can start a fresh payment for the same amount.
 */
export async function rejectPayment(
  paymentId: string,
  adminUserId: string,
  reasonCode: PaymentRejectionReasonValue,
  notes?: string,
): Promise<PaymentRecord> {
  const updated = await db.transaction(async (tx) => {
    const [locked] = await tx
      .select()
      .from(payment)
      .where(eq(payment.id, paymentId))
      .limit(1)
      .for('update');

    if (!locked) {
      throw new NotFoundError('Payment', paymentId);
    }
    if (locked.status !== 'SUBMITTED') {
      throw new DomainError(409, 'payment_not_submitted', 'PAYMENT_NOT_SUBMITTED');
    }
    if (!paymentRejectionReasonValues.includes(reasonCode)) {
      throw new DomainError(400, 'invalid rejection reason', 'INVALID_REJECTION_REASON');
    }

    const trimmedNotes = notes?.trim() || null;
    const [paymentUpdated] = await tx
      .update(payment)
      .set({
        status: 'REJECTED',
        rejectedAt: new Date().toISOString(),
        rejectionReasonCode: reasonCode,
        rejectionReason: trimmedNotes,
      })
      .where(eq(payment.id, paymentId))
      .returning();

    if (!paymentUpdated) {
      throw new Error('failed to reject payment');
    }

    return paymentUpdated;
  });

  await logAudit({
    userId: adminUserId,
    action: AuditActionType.UPDATE,
    entityType: 'Payment',
    entityId: updated.id,
    oldValues: { status: 'SUBMITTED' },
    newValues: { status: 'REJECTED', reasonCode, notes: updated.rejectionReason },
  });

  const reasonLabel = PAYMENT_REJECTION_LABELS[reasonCode];
  const notesSuffix = updated.rejectionReason ? ` ${updated.rejectionReason}.` : '';
  await createNotification({
    recipientId: updated.userId,
    type: NotificationType.PAYMENT_REJECTED,
    title: 'Payment rejected',
    body: `Your payment of ₹${updated.amount} was rejected: ${reasonLabel}.${notesSuffix} You can start a new payment.`,
  });

  return updated;
}

/** Institute UPI details handed to the app so it can build the UPI URI. */
export function getPaymentConfig(): { upiVpa: string; payeeName: string } {
  const upiVpa = process.env.UPI_VPA?.trim();
  const payeeName = process.env.UPI_PAYEE_NAME?.trim();

  if (!upiVpa) {
    throw new Error('UPI_VPA is required');
  }
  if (!payeeName) {
    throw new Error('UPI_PAYEE_NAME is required');
  }

  return { upiVpa, payeeName };
}
