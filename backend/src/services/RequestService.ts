import { randomInt } from 'node:crypto';
import { and, desc, eq, inArray } from 'drizzle-orm';
import { db } from '../drizzle/db.js';
import { component, request, requestItem, user } from '../drizzle/schema.js';
import { RequestStatus, UserRole } from '../utils/enums.js';
import type { RequestStatusValue } from '../utils/enums.js';
import { notifyRequestsUpdated } from '../utils/events.js';
import {
  ExcessReturnQuantityError,
  InsufficientQuantityError,
  NotFoundError,
} from '../utils/errors.js';

export const REQUEST_RETURN_LIMIT_MS = 30 * 24 * 60 * 60 * 1000;

export interface RequestItemInput {
  componentId: string;
  quantity: number;
}

export interface IssueItemInput {
  componentId: string;
  quantity: number;
}

export interface ReturnItemInput {
  componentId: string;
  quantity: number;
}

export async function fetchRequestWithItems(id: string) {
  const [row] = await db.query.request.findMany({
    where: eq(request.id, id),
    with: { requestItems: { with: { component: true } } },
  });
  return row;
}

export type RequestWithRelations = Awaited<ReturnType<typeof fetchRequestWithItems>>;

export async function fetchFullRequest(id: string) {
  const [row] = await db.query.request.findMany({
    where: eq(request.id, id),
    with: {
      requestItems: { with: { component: true } },
      user_userId: {
        columns: { id: true, email: true, name: true, role: true, batch: true, branch: true },
      },
      user_targetFacultyId: {
        columns: { id: true, email: true, name: true, role: true, batch: true, branch: true },
      },
    },
  });
  return row;
}

export type FullRequest = Awaited<ReturnType<typeof fetchFullRequest>>;

interface OtpEntry {
  requestId: string;
  expiresAt: number;
}

const otpStore = new Map<string, OtpEntry>();

export function generateRequestOtp(requestId: string): { otp: string; expiresAt: string } {
  const now = Date.now();
  for (const [code, entry] of otpStore.entries()) {
    if (entry.expiresAt < now) {
      otpStore.delete(code);
    }
  }

  for (const [code, entry] of otpStore.entries()) {
    if (entry.requestId === requestId && entry.expiresAt > now) {
      return {
        otp: code,
        expiresAt: new Date(entry.expiresAt).toISOString(),
      };
    }
  }

  let code: string;
  do {
    code = randomInt(1000, 10000).toString();
  } while (otpStore.has(code));

  const expiresAtMs = now + 10 * 60 * 1000;
  otpStore.set(code, {
    requestId,
    expiresAt: expiresAtMs,
  });

  return {
    otp: code,
    expiresAt: new Date(expiresAtMs).toISOString(),
  };
}

export function consumeOtp(otp: string): string | null {
  const entry = otpStore.get(otp.trim());
  if (!entry) return null;
  if (entry.expiresAt < Date.now()) {
    otpStore.delete(otp.trim());
    return null;
  }
  const requestId = entry.requestId;
  otpStore.delete(otp.trim());
  return requestId;
}

export function invalidateOtpForRequest(requestId: string) {
  for (const [code, entry] of otpStore.entries()) {
    if (entry.requestId === requestId) {
      otpStore.delete(code);
    }
  }
}

export function shapeRequest(row: NonNullable<FullRequest>) {
  return {
    ...row,
    items: row.requestItems,
    user: row.user_userId,
    targetFaculty: row.user_targetFacultyId,
  };
}

export async function fetchExactAndShapeRequest(id: string) {
  const row = await fetchFullRequest(id);
  return row ? shapeRequest(row) : null;
}

export async function fetchAndShapeRequest(query: string) {
  const row = await fetchFullRequestByOtpOrId(query);
  return row ? shapeRequest(row) : null;
}

export async function fetchFullRequestByOtpOrId(query: string) {
  const trimmed = query.trim();
  if (!trimmed) return null;

  let row = await fetchFullRequest(trimmed);
  if (row) return row;

  if (/^\d{4}$/.test(trimmed)) {
    const entry = otpStore.get(trimmed);
    if (entry && entry.expiresAt > Date.now()) {
      row = await fetchFullRequest(entry.requestId);
      if (row) return row;
    }
  }

  const [prefixRow] = await db.query.request.findMany({
    where: (r, { ilike }) => ilike(r.id, `${trimmed}%`),
    orderBy: desc(request.createdAt),
    with: {
      requestItems: { with: { component: true } },
      user_userId: {
        columns: { id: true, email: true, name: true, role: true, batch: true, branch: true },
      },
      user_targetFacultyId: {
        columns: { id: true, email: true, name: true, role: true, batch: true, branch: true },
      },
    },
  });

  return prefixRow || null;
}

export async function updateRequestStatus(id: string, status: RequestStatusValue) {
  await db
    .update(request)
    .set({ status, updatedAt: new Date().toISOString() })
    .where(eq(request.id, id));
  notifyRequestsUpdated();
}

export async function validateFacultyExists(facultyId: string): Promise<boolean> {
  const facultyRow = await db.query.user.findFirst({
    columns: { id: true },
    where: (u, { eq, and }) => and(eq(u.id, facultyId), eq(u.role, UserRole.FACULTY)),
  });
  return Boolean(facultyRow);
}

export async function validateComponentsExist(componentIds: string[]): Promise<boolean> {
  const existingComponents = await db
    .select({ id: component.id })
    .from(component)
    .where(inArray(component.id, componentIds));
  return existingComponents.length === componentIds.length;
}

export async function issueRequestTransaction(
  existingRequest: { id: string } | NonNullable<RequestWithRelations>,
  issueItems?: IssueItemInput[],
) {
  const fulfilledAt = new Date().toISOString();
  await db.transaction(async (tx) => {
    const [lockedRequest] = await tx
      .select()
      .from(request)
      .where(eq(request.id, existingRequest.id))
      .for('update');

    if (!lockedRequest) {
      throw new NotFoundError('Request', existingRequest.id);
    }

    const requestItems = await tx
      .select()
      .from(requestItem)
      .where(eq(requestItem.requestId, existingRequest.id))
      .orderBy(requestItem.id)
      .for('update');

    let hasRemainingToFulfill = false;

    for (const item of requestItems) {
      const requestedQty = item.quantity;
      const alreadyFulfilled = item.fulfilledQuantity ?? 0;
      const remainingToFulfill = requestedQty - alreadyFulfilled;

      if (remainingToFulfill <= 0) {
        continue;
      }

      const issueItem = issueItems?.find((i) => i.componentId === item.componentId);
      const issueQty = issueItem
        ? Math.min(issueItem.quantity, remainingToFulfill)
        : issueItems
          ? 0
          : remainingToFulfill;

      if (issueQty <= 0) {
        hasRemainingToFulfill = true;
        continue;
      }

      const [lockedComp] = await tx
        .select({
          id: component.id,
          name: component.name,
          availableQuantity: component.availableQuantity,
          totalQuantity: component.totalQuantity,
        })
        .from(component)
        .where(eq(component.id, item.componentId))
        .for('update');

      if (!lockedComp || lockedComp.availableQuantity < issueQty) {
        throw new InsufficientQuantityError(lockedComp?.name ?? 'unknown', issueQty);
      }

      const newFulfilled = alreadyFulfilled + issueQty;
      await tx
        .update(requestItem)
        .set({
          fulfilledQuantity: newFulfilled,
          updatedAt: fulfilledAt,
        })
        .where(eq(requestItem.id, item.id));

      await tx
        .update(component)
        .set({
          availableQuantity: lockedComp.availableQuantity - issueQty,
          updatedAt: fulfilledAt,
        })
        .where(eq(component.id, item.componentId));

      if (newFulfilled < requestedQty) {
        hasRemainingToFulfill = true;
      }
    }

    const returnDueAt = new Date(Date.now() + REQUEST_RETURN_LIMIT_MS).toISOString();
    const newStatus = hasRemainingToFulfill ? RequestStatus.PARTIALLY_ISSUED : RequestStatus.ISSUED;

    await tx
      .update(request)
      .set({
        status: newStatus,
        updatedAt: fulfilledAt,
        fulfilledAt,
        returnDueAt,
      })
      .where(eq(request.id, existingRequest.id));
    notifyRequestsUpdated();
  });
}

export async function requestForRenewalTransaction(
  existingRequest: { id: string },
  lastRenewReason: string | undefined,
) {
  const requestedRenewalAt = new Date().toISOString();
  await db.transaction(async (tx) => {
    const [lockedRequest] = await tx
      .select()
      .from(request)
      .where(eq(request.id, existingRequest.id))
      .for('update');

    if (!lockedRequest) {
      throw new NotFoundError('Request', existingRequest.id);
    }

    await tx
      .update(request)
      .set({
        status: RequestStatus.REQUESTED_RENEW,
        updatedAt: requestedRenewalAt,
        lastRenewReason,
        renewalRequestedAt: requestedRenewalAt,
      })
      .where(eq(request.id, existingRequest.id));
    notifyRequestsUpdated();
  });
}

export async function approveRenewRequestTransaction(existingRequest: { id: string }) {
  const renewedAt = new Date().toISOString();
  const newReturnDueAt = new Date(Date.now() + REQUEST_RETURN_LIMIT_MS).toISOString();

  await db.transaction(async (tx) => {
    const [lockedRequest] = await tx
      .select()
      .from(request)
      .where(eq(request.id, existingRequest.id))
      .for('update');

    if (!lockedRequest) {
      throw new NotFoundError('Request', existingRequest.id);
    }

    await tx
      .update(request)
      .set({
        status: RequestStatus.RENEWED,
        updatedAt: renewedAt,
        returnDueAt: newReturnDueAt,
        lastRenewDate: renewedAt,
        renewalRequestedAt: null,
        renewalReminderSentAt: null,
      })
      .where(eq(request.id, existingRequest.id));
    notifyRequestsUpdated();
  });
}

export async function rejectRenewRequestTransaction(
  existingRequest: { id: string },
  revertStatus: RequestStatusValue,
) {
  const rejectedAt = new Date().toISOString();
  await db.transaction(async (tx) => {
    const [lockedRequest] = await tx
      .select()
      .from(request)
      .where(eq(request.id, existingRequest.id))
      .for('update');

    if (!lockedRequest) {
      throw new NotFoundError('Request', existingRequest.id);
    }

    await tx
      .update(request)
      .set({
        status: revertStatus,
        updatedAt: rejectedAt,
      })
      .where(eq(request.id, existingRequest.id));
    notifyRequestsUpdated();
  });
}

export async function returnRequestTransaction(
  existingRequest: { id: string } | NonNullable<RequestWithRelations>,
  returnItems?: ReturnItemInput[],
  options: { preserveStatus?: boolean } = {},
) {
  const returnedAt = new Date().toISOString();
  await db.transaction(async (tx) => {
    const [lockedRequest] = await tx
      .select()
      .from(request)
      .where(eq(request.id, existingRequest.id))
      .for('update');

    if (!lockedRequest) {
      throw new NotFoundError('Request', existingRequest.id);
    }

    const requestItems = await tx
      .select()
      .from(requestItem)
      .where(eq(requestItem.requestId, existingRequest.id))
      .orderBy(requestItem.id)
      .for('update');

    let hasIssuedRemaining = false;
    let hasAnyReturned = false;

    for (const item of requestItems) {
      const issuedQty = item.fulfilledQuantity ?? 0;
      const alreadyReturnedQty = item.returnedQuantity ?? 0;
      const alreadyWrittenOffQty = item.writtenOffQuantity ?? 0;
      // What can still come back, including stock written off on expiry.
      const outstandingQty = issuedQty - alreadyReturnedQty;
      // What the student physically holds: written-off stock is not with them.
      const currentlyHeldQty = outstandingQty - alreadyWrittenOffQty;

      if (outstandingQty <= 0) {
        continue;
      }

      const returnItem = returnItems?.find((i) => i.componentId === item.componentId);

      if (returnItem && returnItem.quantity > outstandingQty) {
        throw new ExcessReturnQuantityError(item.componentId, returnItem.quantity, outstandingQty);
      }

      const returnQty = returnItem ? returnItem.quantity : returnItems ? 0 : outstandingQty;

      if (returnQty <= 0) {
        hasIssuedRemaining = true;
        continue;
      }

      hasAnyReturned = true;

      const [lockedComp] = await tx
        .select({
          id: component.id,
          name: component.name,
          availableQuantity: component.availableQuantity,
          totalQuantity: component.totalQuantity,
        })
        .from(component)
        .where(eq(component.id, item.componentId))
        .for('update');

      if (!lockedComp) {
        throw new NotFoundError('Component', item.componentId);
      }

      // Stock coming back after a write-off is reclaimed: it returns to both
      // available and total, so the component stops counting it as lost.
      const reclaimedQty = Math.min(
        Math.max(0, returnQty - currentlyHeldQty),
        alreadyWrittenOffQty,
      );
      const newReturned = alreadyReturnedQty + returnQty;
      const newWrittenOff = alreadyWrittenOffQty - reclaimedQty;

      await tx
        .update(requestItem)
        .set({
          returnedQuantity: newReturned,
          writtenOffQuantity: newWrittenOff,
          updatedAt: returnedAt,
        })
        .where(eq(requestItem.id, item.id));

      const nextAvailable = lockedComp.availableQuantity + returnQty;
      const nextTotal = Math.max(lockedComp.totalQuantity + reclaimedQty, nextAvailable);

      await tx
        .update(component)
        .set({
          availableQuantity: nextAvailable,
          totalQuantity: nextTotal,
          updatedAt: returnedAt,
        })
        .where(eq(component.id, item.componentId));

      if (issuedQty - newReturned > 0) {
        hasIssuedRemaining = true;
      }
    }

    const newStatus =
      hasIssuedRemaining && hasAnyReturned
        ? RequestStatus.PARTIALLY_RETURNED
        : RequestStatus.RETURNED;

    // An expired request keeps EXPIRED after a late return: the deadline was
    // still missed. The stock movement above is what actually matters.
    const statusToSet = options.preserveStatus ? lockedRequest.status : newStatus;

    await tx
      .update(request)
      .set({
        status: statusToSet,
        updatedAt: returnedAt,
        returnedAt: newStatus === RequestStatus.RETURNED ? returnedAt : null,
      })
      .where(eq(request.id, existingRequest.id));
    notifyRequestsUpdated();
  });
}

export async function createRequest(
  userId: string,
  targetFacultyId: string,
  projectTitle: string,
  items: { componentId: string; quantity: number }[],
) {
  const now = new Date().toISOString();
  const requestId = crypto.randomUUID();

  await db.transaction(async (tx) => {
    await tx.insert(request).values({
      id: requestId,
      userId,
      targetFacultyId,
      projectTitle,
      status: RequestStatus.PENDING,
      createdAt: now,
      updatedAt: now,
    });

    await tx.insert(requestItem).values(
      items.map((item) => ({
        id: crypto.randomUUID(),
        requestId,
        componentId: item.componentId,
        quantity: item.quantity,
        createdAt: now,
        updatedAt: now,
      })),
    );
  });

  return requestId;
}

export async function getFaculty() {
  return await db
    .select({ id: user.id, email: user.email, name: user.name, role: user.role })
    .from(user)
    .where(eq(user.role, UserRole.FACULTY))
    .orderBy(desc(user.createdAt));
}

export async function getRequests(options: {
  userId?: string;
  targetFacultyId?: string;
  status?: RequestStatusValue;
}) {
  const conditions = [];

  if (options.status) {
    conditions.push(eq(request.status, options.status));
  }

  if (options.targetFacultyId) {
    conditions.push(eq(request.targetFacultyId, options.targetFacultyId));
  } else if (options.userId) {
    conditions.push(eq(request.userId, options.userId));
  }

  const whereClause = conditions.length > 0 ? and(...conditions) : undefined;

  const rows = await db.query.request.findMany({
    where: whereClause,
    orderBy: desc(request.createdAt),
    with: {
      requestItems: { with: { component: true } },
      user_userId: {
        columns: { id: true, email: true, name: true, role: true, batch: true, branch: true },
      },
      user_targetFacultyId: {
        columns: { id: true, email: true, name: true, role: true, batch: true, branch: true },
      },
    },
  });

  return rows.map(shapeRequest);
}

export async function deleteRequest(id: string) {
  await db.transaction(async (tx) => {
    await tx.delete(requestItem).where(eq(requestItem.requestId, id));
    await tx.delete(request).where(eq(request.id, id));
  });
  notifyRequestsUpdated();
}
