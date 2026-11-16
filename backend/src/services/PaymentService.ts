import { and, eq, gt, isNotNull, isNull } from 'drizzle-orm';
import { db } from '../drizzle/db.js';
import { payment, paymentAllocation, request } from '../drizzle/schema.js';
import { DomainError, ForbiddenError, NotFoundError } from '../utils/errors.js';

export type PaymentRecord = typeof payment.$inferSelect;
export type PaymentAllocationRecord = typeof paymentAllocation.$inferSelect;

export type PaymentWithAllocations = PaymentRecord & {
  allocations: PaymentAllocationRecord[];
};

/**
 * Returns the student's PENDING payment, or creates one covering every unpaid
 * finalized fine. The amount is always computed here from the database; any
 * amount supplied by a client is ignored.
 */
export async function createPaymentIntent(userId: string): Promise<PaymentWithAllocations> {
  return db.transaction(async (tx) => {
    const [existing] = await tx
      .select()
      .from(payment)
      .where(and(eq(payment.userId, userId), eq(payment.status, 'PENDING')))
      .limit(1)
      .for('update');

    if (existing) {
      const allocations = await tx
        .select()
        .from(paymentAllocation)
        .where(eq(paymentAllocation.paymentId, existing.id));

      return { ...existing, allocations };
    }

    // Lock the fines so a concurrent return cannot change lateFeeAccrued
    // between this read and the allocation insert.
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
      throw new DomainError(400, 'no unpaid fines', 'NO_UNPAID_FINES');
    }

    const total = fines.reduce((sum, fine) => sum + fine.lateFeeAccrued, 0);
    const now = new Date().toISOString();
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

    return { ...created, allocations };
  });
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
