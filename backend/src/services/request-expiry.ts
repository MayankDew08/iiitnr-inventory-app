import { and, eq, inArray, isNotNull, lte } from 'drizzle-orm';
import { db } from '../drizzle/db.js';
import { component, request, requestItem } from '../drizzle/schema.js';
import { logAudit } from '../utils/audit.js';
import { AuditActionType, RequestStatus } from '../utils/enums.js';
import type { RequestStatusValue } from '../utils/enums.js';
import { HOURLY_SWEEP_INTERVAL_MS, startIntervalSweep } from '../utils/sweep.js';

export const ACTIVE_LOAN_STATUSES: RequestStatusValue[] = [
  RequestStatus.ISSUED,
  RequestStatus.PARTIALLY_ISSUED,
  RequestStatus.PARTIALLY_RETURNED,
  RequestStatus.RENEWED,
];

interface ExpireOverdueRequestsOptions {
  requestId?: string;
  now?: Date;
}

export interface ExpiredRequest {
  id: string;
  userId: string;
}

/**
 * Marks one overdue request EXPIRED and writes its outstanding stock off the
 * component totals, atomically.
 *
 * Returns false when the request is no longer expirable (already handled, or
 * the due date moved), which makes repeated sweeps a no-op.
 */
async function expireSingleRequest(requestId: string, nowIso: string): Promise<boolean> {
  const writeOffs: { componentId: string; quantity: number; componentName: string }[] = [];

  const didExpire = await db.transaction(async (tx) => {
    // Lock order matches returnRequestTransaction: request -> items -> component.
    const [lockedRequest] = await tx
      .select({ id: request.id, status: request.status, returnDueAt: request.returnDueAt })
      .from(request)
      .where(eq(request.id, requestId))
      .for('update');

    if (!lockedRequest) return false;
    if (!ACTIVE_LOAN_STATUSES.includes(lockedRequest.status)) return false;
    if (!lockedRequest.returnDueAt || lockedRequest.returnDueAt > nowIso) return false;

    const items = await tx
      .select()
      .from(requestItem)
      .where(eq(requestItem.requestId, requestId))
      .orderBy(requestItem.id)
      .for('update');

    for (const item of items) {
      const outstanding = (item.fulfilledQuantity ?? 0) - (item.returnedQuantity ?? 0);
      const held = outstanding - (item.writtenOffQuantity ?? 0);

      if (held <= 0) continue;

      const [lockedComponent] = await tx
        .select({
          id: component.id,
          name: component.name,
          totalQuantity: component.totalQuantity,
        })
        .from(component)
        .where(eq(component.id, item.componentId))
        .for('update');

      if (!lockedComponent) continue;

      await tx
        .update(requestItem)
        .set({ writtenOffQuantity: (item.writtenOffQuantity ?? 0) + held, updatedAt: nowIso })
        .where(eq(requestItem.id, item.id));

      // Written-off stock leaves totalQuantity but not availableQuantity: it was
      // already deducted from available when it was issued.
      await tx
        .update(component)
        .set({
          totalQuantity: Math.max(0, lockedComponent.totalQuantity - held),
          updatedAt: nowIso,
        })
        .where(eq(component.id, item.componentId));

      writeOffs.push({
        componentId: item.componentId,
        quantity: held,
        componentName: lockedComponent.name,
      });
    }

    await tx
      .update(request)
      .set({ status: RequestStatus.EXPIRED, updatedAt: nowIso })
      .where(eq(request.id, requestId));

    return true;
  });

  if (didExpire) {
    for (const writeOff of writeOffs) {
      await logAudit({
        action: AuditActionType.INVENTORY_ADJUST,
        entityType: 'Component',
        entityId: writeOff.componentId,
        newValues: { totalQuantityDelta: -writeOff.quantity },
        metadata: {
          reason: 'EXPIRED_NOT_RETURNED',
          requestId,
          componentName: writeOff.componentName,
          quantity: writeOff.quantity,
        },
      });
    }
  }

  return didExpire;
}

export async function expireOverdueRequests(
  options: ExpireOverdueRequestsOptions = {},
): Promise<ExpiredRequest[]> {
  const nowIso = (options.now ?? new Date()).toISOString();
  const conditions = [
    inArray(request.status, ACTIVE_LOAN_STATUSES),
    isNotNull(request.returnDueAt),
    lte(request.returnDueAt, nowIso),
  ];

  if (options.requestId) {
    conditions.push(eq(request.id, options.requestId));
  }

  const candidates = await db
    .select({ id: request.id, userId: request.userId })
    .from(request)
    .where(and(...conditions));

  const expired: ExpiredRequest[] = [];
  for (const candidate of candidates) {
    if (await expireSingleRequest(candidate.id, nowIso)) {
      expired.push(candidate);
    }
  }

  return expired;
}

export function startRequestExpirySweep(
  onError: (err: unknown) => void,
  onExpired?: (expired: ExpiredRequest[]) => void,
  intervalMs: number = HOURLY_SWEEP_INTERVAL_MS,
) {
  return startIntervalSweep(
    async () => {
      const expired = await expireOverdueRequests();
      if (expired.length > 0) {
        onExpired?.(expired);
      }
    },
    onError,
    intervalMs,
  );
}
