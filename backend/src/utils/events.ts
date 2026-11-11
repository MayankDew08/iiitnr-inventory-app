import { EventEmitter } from 'node:events';

export const events = new EventEmitter();

export const EventType = {
  REQUESTS_UPDATED: 'requests_updated',
  NOTIFICATION_CREATED: 'notification_created',
} as const;

export function notifyRequestsUpdated() {
  events.emit(EventType.REQUESTS_UPDATED);
}

export interface NotificationCreatedEvent {
  recipientId: string;
  notification: {
    id: string;
    type: string;
    title: string;
    body: string | null;
    requestId: string | null;
    createdAt: string;
    read: boolean;
  };
}

export function notifyNotificationCreated(event: NotificationCreatedEvent) {
  events.emit(EventType.NOTIFICATION_CREATED, event);
}
