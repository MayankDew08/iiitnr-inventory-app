import { eq, inArray } from 'drizzle-orm';
import { db } from '../src/drizzle/db.js';
import {
  auditLog,
  component,
  notification,
  request,
  requestItem,
  user,
} from '../src/drizzle/schema.js';
import type { RequestStatusValue } from '../src/utils/enums.js';
import { ComponentCategory, Location, UserRole } from '../src/utils/enums.js';

const now = () => new Date().toISOString();

export async function createUser(data: {
  email: string;
  passwordHash?: string;
  name?: string | null;
  role: (typeof UserRole)[keyof typeof UserRole];
  googleId?: string | null;
}) {
  const n = now();
  const [u] = await db
    .insert(user)
    .values({
      id: crypto.randomUUID(),
      email: data.email,
      passwordHash: data.passwordHash ?? null,
      name: data.name ?? null,
      role: data.role,
      googleId: data.googleId ?? null,
      createdAt: n,
      updatedAt: n,
    })
    .returning();
  return u;
}

export async function createComponent(data: {
  name: string;
  description?: string | null;
  imageUrl?: string | null;
  totalQuantity?: number;
  availableQuantity?: number;
  category?: (typeof ComponentCategory)[keyof typeof ComponentCategory] | null;
  location?: (typeof Location)[keyof typeof Location] | null;
  /** Override for tests that assert ordering; two inserts can share a millisecond. */
  createdAt?: string;
}) {
  const n = data.createdAt ?? now();
  const [c] = await db
    .insert(component)
    .values({
      id: crypto.randomUUID(),
      name: data.name,
      description: data.description ?? null,
      imageUrl: data.imageUrl ?? null,
      totalQuantity: data.totalQuantity ?? 0,
      availableQuantity: data.availableQuantity ?? data.totalQuantity ?? 0,
      category: data.category ?? null,
      location: data.location ?? null,
      createdAt: n,
      updatedAt: n,
    })
    .returning();
  return c;
}

export async function createRequest(data: {
  userId: string;
  targetFacultyId: string;
  projectTitle: string;
  status?: RequestStatusValue;
  items: {
    componentId: string;
    quantity: number;
    fulfilledQuantity?: number;
    returnedQuantity?: number;
  }[];
}) {
  const n = now();
  const requestId = crypto.randomUUID();
  await db.insert(request).values({
    id: requestId,
    userId: data.userId,
    targetFacultyId: data.targetFacultyId,
    projectTitle: data.projectTitle,
    status: data.status ?? 'PENDING',
    createdAt: n,
    updatedAt: n,
  });
  await db.insert(requestItem).values(
    data.items.map((item) => ({
      id: crypto.randomUUID(),
      requestId,
      componentId: item.componentId,
      quantity: item.quantity,
      fulfilledQuantity: item.fulfilledQuantity ?? 0,
      returnedQuantity: item.returnedQuantity ?? 0,
      createdAt: n,
      updatedAt: n,
    })),
  );
  const [r] = await db.select().from(request).where(eq(request.id, requestId)).limit(1);
  return r;
}

export async function deleteAllData() {
  await db.delete(notification);
  await db.delete(auditLog);
  await db.delete(requestItem);
  await db.delete(request);
  await db.delete(component);
}

export async function deleteUsers(ids: string[]) {
  if (ids.length > 0) {
    await db.delete(auditLog).where(inArray(auditLog.userId, ids));
    await db.delete(notification).where(inArray(notification.recipientId, ids));
    await db.delete(user).where(inArray(user.id, ids));
  }
}

export async function deleteComponents(ids: string[]) {
  if (ids.length > 0) {
    await db.delete(component).where(inArray(component.id, ids));
  }
}

export async function deleteAllRequests() {
  await db.delete(notification);
  await db.delete(requestItem);
  await db.delete(request);
}

export async function findRequestById(id: string) {
  const row = await db.query.request.findFirst({
    where: eq(request.id, id),
    with: { requestItems: true },
  });

  if (!row) {
    return null;
  }

  return {
    ...row,
    items: row.requestItems,
  };
}

export async function findComponentById(id: string) {
  const [c] = await db.select().from(component).where(eq(component.id, id)).limit(1);
  return c ?? null;
}

export { UserRole, ComponentCategory, Location };
