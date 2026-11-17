import { and, desc, eq, gt, inArray, isNotNull, isNull } from 'drizzle-orm';
import { db } from '../drizzle/db.js';
import { payment, paymentAllocation, request } from '../drizzle/schema.js';
import { DomainError, ForbiddenError, NotFoundError } from '../utils/errors.js';

export type PaymentRecord = typeof payment.$inferSelect;
export type PaymentAllocationRecord = typeof paymentAllocation.$inferSelect;

export type PaymentWithAllocations = PaymentRecord & {
  allocations: PaymentAllocationRecord[];
};

// The "delete the dead PENDING" branch must commit before the 400 is raised;
// throwing inside the transaction would roll the delete back.
type CreateIntentResult =
  | { ok: true; payment: PaymentWithAllocations }
  | { ok: false; error: DomainError };

/**
 * Returns the student's PENDING payment, refreshed against the current set of
 * unpaid finalized fines, or creates one covering them. The amount is always
 * computed here from the database; any amount supplied by a client is ignored.
 */
export async function createPaymentIntent(userId: string): Promise<PaymentWithAllocations> {
  const result = await db.transaction(async (tx): Promise<CreateIntentResult> => {
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
      const fineIds = fines
        .map((fine) => fine.id)
        .sort();
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

  if (!result.ok) {
    throw result.error;
  }

  return result.payment;
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
      and(eq(payment.userId, userId), inArray(payment.status, ['PENDING', 'SUBMITTED', 'REJECTED'])),
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
 * submitted: only PENDING → SUBMITTED is allowed.
 */
export async function submitPayment(
  paymentId: string,
  userId: string,
  utr: string,
  proofImageUrl: string,
): Promise<PaymentRecord> {
  return db.transaction(async (tx) => {
    const [locked] = await tx
      .select()
      .from(payment)
      .where(eq(payment.id, paymentId))
      .for('update');

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

    const [updated] = await tx
      .update(payment)
      .set({
        status: 'SUBMITTED',
        utr,
        proofImageUrl,
        submittedAt: new Date().toISOString(),
      })
      .where(eq(payment.id, paymentId))
      .returning();

    if (!updated) {
      throw new Error('failed to submit payment');
    }

    return updated;
  });
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
