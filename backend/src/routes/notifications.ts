import type { FastifyPluginCallback, FastifyRequest, FastifyReply } from 'fastify';
import type { EventMessage } from 'fastify-sse-v2';
import { pushable } from 'it-pushable';
import { requireAuth } from '../middleware/auth.js';
import { events, EventType } from '../utils/events.js';
import type { NotificationCreatedEvent } from '../utils/events.js';
import {
  listNotifications,
  countUnreadNotifications,
  markNotificationRead,
  markAllNotificationsRead,
  shapeNotification,
} from '../services/NotificationService.js';

function getCurrentUserId(req: FastifyRequest): string | null {
  return (req.user as { sub?: string })?.sub ?? null;
}

function getLimit(req: FastifyRequest): number {
  const query = req.query as { limit?: string };
  const parsed = Number.parseInt(query?.limit ?? '', 10);
  return Number.isFinite(parsed) ? parsed : 50;
}

async function handleListNotifications(req: FastifyRequest, reply: FastifyReply) {
  const userId = getCurrentUserId(req);
  if (!userId) {
    return reply.code(401).send({ error: 'unauthorized' });
  }

  const rows = await listNotifications(userId, getLimit(req));
  return reply.send({
    notifications: rows.map(shapeNotification),
    unreadCount: await countUnreadNotifications(userId),
  });
}

async function handleUnreadCount(req: FastifyRequest, reply: FastifyReply) {
  const userId = getCurrentUserId(req);
  if (!userId) {
    return reply.code(401).send({ error: 'unauthorized' });
  }

  return reply.send({ unreadCount: await countUnreadNotifications(userId) });
}

async function handleMarkRead(req: FastifyRequest, reply: FastifyReply) {
  const userId = getCurrentUserId(req);
  if (!userId) {
    return reply.code(401).send({ error: 'unauthorized' });
  }

  const params = req.params as { id?: string };
  const id = params?.id;
  if (!id) {
    return reply.code(400).send({ error: 'notification id is required' });
  }

  const updated = await markNotificationRead(userId, id);
  if (!updated) {
    return reply.code(404).send({ error: 'notification not found' });
  }

  return reply.send({ ok: true });
}

async function handleMarkAllRead(req: FastifyRequest, reply: FastifyReply) {
  const userId = getCurrentUserId(req);
  if (!userId) {
    return reply.code(401).send({ error: 'unauthorized' });
  }

  const count = await markAllNotificationsRead(userId);
  return reply.send({ ok: true, count });
}

function handleNotificationStream(req: FastifyRequest, reply: FastifyReply) {
  const currentUserId = getCurrentUserId(req);
  if (!currentUserId) {
    return reply.code(401).send({ error: 'unauthorized' });
  }

  const p = pushable<EventMessage>({ objectMode: true });
  const onNotification = (event: NotificationCreatedEvent) => {
    if (event.recipientId === currentUserId) {
      p.push({ data: JSON.stringify(event.notification) });
    }
  };

  events.on(EventType.NOTIFICATION_CREATED, onNotification);

  req.raw.on('close', () => {
    events.off(EventType.NOTIFICATION_CREATED, onNotification);
    p.end();
  });

  return reply.sse(p);
}

const notificationsRoutes: FastifyPluginCallback = (app, _opts, done) => {
  app.get('/notifications', { preHandler: requireAuth }, (req, reply) =>
    handleListNotifications(req, reply),
  );

  app.get('/notifications/unread-count', { preHandler: requireAuth }, (req, reply) =>
    handleUnreadCount(req, reply),
  );

  app.put('/notifications/:id/read', { preHandler: requireAuth }, (req, reply) =>
    handleMarkRead(req, reply),
  );

  app.put('/notifications/read-all', { preHandler: requireAuth }, (req, reply) =>
    handleMarkAllRead(req, reply),
  );

  app.get('/notifications/events', { preHandler: requireAuth }, (req, reply) =>
    handleNotificationStream(req, reply),
  );

  done();
};

export default notificationsRoutes;
