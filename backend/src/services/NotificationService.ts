import { and, desc, eq, inArray, sql } from 'drizzle-orm';
import { db } from '../drizzle/db.js';
import { notification, user } from '../drizzle/schema.js';
import { RequestStatus, UserRole } from '../utils/enums.js';
import { notifyNotificationCreated } from '../utils/events.js';
import { fetchExactAndShapeRequest } from './RequestService.js';
import type { ExpiredRequest } from './request-expiry.js';

export const NotificationType = {
  REQUEST_SUBMITTED: 'REQUEST_SUBMITTED',
  REQUEST_APPROVED: 'REQUEST_APPROVED',
  REQUEST_REJECTED: 'REQUEST_REJECTED',
  REQUEST_ISSUED: 'REQUEST_ISSUED',
  REQUEST_PARTIALLY_ISSUED: 'REQUEST_PARTIALLY_ISSUED',
  REQUEST_RETURNED: 'REQUEST_RETURNED',
  REQUEST_PARTIALLY_RETURNED: 'REQUEST_PARTIALLY_RETURNED',
  REQUEST_EXPIRED: 'REQUEST_EXPIRED',
  RENEWAL_REQUESTED: 'RENEWAL_REQUESTED',
  RENEWAL_APPROVED: 'RENEWAL_APPROVED',
  RENEWAL_REJECTED: 'RENEWAL_REJECTED',
} as const;

export type NotificationTypeValue = (typeof NotificationType)[keyof typeof NotificationType];

export interface ShapedRequestForNotification {
  id: string;
  projectTitle: string;
  status: string;
  returnDueAt: string | null;
  userId: string;
  targetFacultyId: string;
  user: { name: string | null; email: string; batch: string | null } | null;
  targetFaculty: { name: string | null; email: string } | null;
  items?: { component: { name: string } | null }[];
}

interface NotificationRecipient {
  id: string;
  type: NotificationTypeValue;
  title: string;
  body: string | null;
}

interface NotificationContent {
  type: NotificationTypeValue;
  title: string;
  body: string | null;
}

interface NotificationPlan {
  student?: NotificationContent;
  faculty?: NotificationContent;
  staff?: NotificationContent;
}

function requestLabel(req: ShapedRequestForNotification): string {
  return `"${req.projectTitle}" (${req.status.replace(/_/g, ' ')})`;
}

function formatStudent(req: ShapedRequestForNotification): string {
  const student = req.user;
  if (!student) return 'A student';
  return student.name ? `${student.name} (${student.email})` : student.email;
}

function itemNames(req: ShapedRequestForNotification): string {
  return (
    req.items
      ?.map((item) => item.component?.name)
      .filter(Boolean)
      .join(', ') ?? ''
  );
}

function dueDateText(req: ShapedRequestForNotification): string {
  return req.returnDueAt ? req.returnDueAt.slice(0, 10) : 'the due date';
}

export async function getStaffRecipients(): Promise<{ id: string; email: string }[]> {
  return db
    .select({ id: user.id, email: user.email })
    .from(user)
    .where(inArray(user.role, [UserRole.ADMIN, UserRole.LA]));
}

export async function createNotification(input: {
  recipientId: string;
  type: NotificationTypeValue;
  title: string;
  body?: string | null;
  requestId?: string | null;
}): Promise<void> {
  const id = crypto.randomUUID();
  const createdAt = new Date();

  try {
    await db.insert(notification).values({
      id,
      recipientId: input.recipientId,
      type: input.type,
      title: input.title,
      body: input.body ?? null,
      requestId: input.requestId ?? null,
      read: false,
      createdAt: createdAt.toISOString(),
    });
  } catch (err) {
    console.error('Failed to create notification:', err);
    return;
  }

  notifyNotificationCreated({
    recipientId: input.recipientId,
    notification: {
      id,
      type: input.type,
      title: input.title,
      body: input.body ?? null,
      requestId: input.requestId ?? null,
      createdAt: createdAt.toISOString(),
      read: false,
    },
  });
}

async function createNotifications(recipients: NotificationRecipient[], requestId: string) {
  for (const recipient of recipients) {
    await createNotification({
      recipientId: recipient.id,
      type: recipient.type,
      title: recipient.title,
      body: recipient.body,
      requestId,
    });
  }
}

function buildNotificationPlan(req: ShapedRequestForNotification): NotificationPlan {
  switch (req.status) {
    case RequestStatus.PENDING:
      return {
        faculty: {
          type: NotificationType.REQUEST_SUBMITTED,
          title: 'New request awaiting your approval',
          body: `${formatStudent(req)} submitted ${requestLabel(req)} for ${
            itemNames(req) || 'lab components'
          }.`,
        },
        staff: {
          type: NotificationType.REQUEST_SUBMITTED,
          title: 'New request submitted',
          body: `${formatStudent(req)} submitted ${requestLabel(req)} for ${
            itemNames(req) || 'lab components'
          }.`,
        },
      };

    case RequestStatus.APPROVED:
      return {
        student: {
          type: NotificationType.REQUEST_APPROVED,
          title: 'Your request was approved',
          body: `${requestLabel(req)} has been approved. Visit the lab to collect your items.`,
        },
      };

    case RequestStatus.REJECTED:
      return {
        student: {
          type: NotificationType.REQUEST_REJECTED,
          title: 'Your request was rejected',
          body: `${requestLabel(req)} was rejected by ${
            req.targetFaculty
              ? (req.targetFaculty.name ?? req.targetFaculty.email)
              : 'the nominating faculty'
          }.`,
        },
      };

    case RequestStatus.ISSUED:
      return {
        student: {
          type: NotificationType.REQUEST_ISSUED,
          title: 'Items issued',
          body: `${requestLabel(req)} has been issued. Return by ${dueDateText(req)}.`,
        },
      };

    case RequestStatus.PARTIALLY_ISSUED:
      return {
        student: {
          type: NotificationType.REQUEST_PARTIALLY_ISSUED,
          title: 'Items partially issued',
          body: `Some items for ${requestLabel(req)} have been issued. Return by ${dueDateText(
            req,
          )}.`,
        },
      };

    case RequestStatus.RETURNED:
      return {
        student: {
          type: NotificationType.REQUEST_RETURNED,
          title: 'Items returned',
          body: `All items for ${requestLabel(req)} have been returned and restocked.`,
        },
      };

    case RequestStatus.PARTIALLY_RETURNED:
      return {
        student: {
          type: NotificationType.REQUEST_PARTIALLY_RETURNED,
          title: 'Items partially returned',
          body: `Some items for ${requestLabel(req)} were returned. Remaining items are due by ${dueDateText(
            req,
          )}.`,
        },
      };

    case RequestStatus.EXPIRED:
      return {
        student: {
          type: NotificationType.REQUEST_EXPIRED,
          title: 'Request expired — return overdue',
          body: `${requestLabel(req)} is overdue. It was due by ${dueDateText(
            req,
          )}. Please return the items as soon as possible.`,
        },
        faculty: {
          type: NotificationType.REQUEST_EXPIRED,
          title: 'A request you supervise has expired',
          body: `${requestLabel(req)} by ${formatStudent(req)} was due by ${dueDateText(
            req,
          )}. The items have been written off the inventory.`,
        },
        staff: {
          type: NotificationType.REQUEST_EXPIRED,
          title: 'Inventory write-off on expiry',
          body: `${requestLabel(req)} by ${formatStudent(req)} expired on ${dueDateText(
            req,
          )}. The outstanding items have been written off the inventory.`,
        },
      };

    case RequestStatus.REQUESTED_RENEW:
      return {
        faculty: {
          type: NotificationType.RENEWAL_REQUESTED,
          title: 'Renewal request awaiting your action',
          body: `${formatStudent(req)} requested a renewal for ${requestLabel(req)}.`,
        },
        staff: {
          type: NotificationType.RENEWAL_REQUESTED,
          title: 'Renewal request submitted',
          body: `${formatStudent(req)} requested a renewal for ${requestLabel(req)}.`,
        },
      };

    case RequestStatus.RENEWED:
      return {
        student: {
          type: NotificationType.RENEWAL_APPROVED,
          title: 'Renewal approved',
          body: `${requestLabel(req)} has been renewed. New return due date: ${dueDateText(req)}.`,
        },
      };

    default:
      return {};
  }
}

async function applyNotificationPlan(
  plan: NotificationPlan,
  req: ShapedRequestForNotification,
  actorUserId?: string,
) {
  const recipients: NotificationRecipient[] = [];

  if (plan.student && req.userId !== actorUserId) {
    recipients.push({ id: req.userId, ...plan.student });
  }
  if (plan.faculty && req.targetFacultyId !== actorUserId) {
    recipients.push({ id: req.targetFacultyId, ...plan.faculty });
  }
  if (plan.staff) {
    for (const staff of await getStaffRecipients()) {
      if (staff.id !== actorUserId) {
        recipients.push({ id: staff.id, ...plan.staff });
      }
    }
  }

  await createNotifications(recipients, req.id);
}

/**
 * Fans out in-app notifications for a request lifecycle transition.
 * Never throws: notification failures must not break the underlying operation.
 */
export async function notifyRequestTransition(
  req: ShapedRequestForNotification,
  actorUserId?: string,
): Promise<void> {
  try {
    await applyNotificationPlan(buildNotificationPlan(req), req, actorUserId);
  } catch (err) {
    console.error('Failed to fan out request notifications:', err);
  }
}

export async function notifyRequestsExpired(expired: ExpiredRequest[]): Promise<void> {
  for (const { id } of expired) {
    const shaped = await fetchExactAndShapeRequest(id);
    if (shaped) {
      await notifyRequestTransition(shaped);
    }
  }
}

export async function notifyRenewalRejected(
  req: ShapedRequestForNotification,
  actorUserId?: string,
): Promise<void> {
  try {
    if (req.userId === actorUserId) return;

    await createNotification({
      recipientId: req.userId,
      type: NotificationType.RENEWAL_REJECTED,
      title: 'Renewal request rejected',
      body: `Your renewal request for "${req.projectTitle}" was not approved. The request keeps its original return due date (${dueDateText(req)}).`,
      requestId: req.id,
    });
  } catch (err) {
    console.error('Failed to send renewal rejection notification:', err);
  }
}

export async function listNotifications(recipientId: string, limit = 50) {
  return db
    .select()
    .from(notification)
    .where(eq(notification.recipientId, recipientId))
    .orderBy(desc(notification.createdAt))
    .limit(Math.min(Math.max(limit, 1), 200));
}

export async function countUnreadNotifications(recipientId: string): Promise<number> {
  const [row] = await db
    .select({ count: sql<number>`count(*)::int` })
    .from(notification)
    .where(and(eq(notification.recipientId, recipientId), eq(notification.read, false)));
  return row?.count ?? 0;
}

export async function markNotificationRead(
  recipientId: string,
  notificationId: string,
): Promise<boolean> {
  const rows = await db
    .update(notification)
    .set({ read: true })
    .where(and(eq(notification.id, notificationId), eq(notification.recipientId, recipientId)))
    .returning({ id: notification.id });
  return rows.length > 0;
}

export async function markAllNotificationsRead(recipientId: string): Promise<number> {
  const rows = await db
    .update(notification)
    .set({ read: true })
    .where(and(eq(notification.recipientId, recipientId), eq(notification.read, false)))
    .returning({ id: notification.id });
  return rows.length;
}

export type NotificationRow = Awaited<ReturnType<typeof listNotifications>>[number];

export function shapeNotification(row: NotificationRow) {
  return {
    id: row.id,
    type: row.type,
    title: row.title,
    body: row.body,
    requestId: row.requestId,
    read: row.read,
    createdAt: row.createdAt,
  };
}
