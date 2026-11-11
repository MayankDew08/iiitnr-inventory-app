import { notifyRequestsUpdated } from '../utils/events.js';
import { sendExpiryNoticesForRequests } from './expiry-notice.js';
import { notifyRequestsExpired } from './NotificationService.js';
import type { ExpiredRequest } from './request-expiry.js';

export function fanOutExpiredRequests(
  expired: ExpiredRequest[],
  onError: (err: unknown, message: string) => void,
): void {
  if (!expired.length) return;

  notifyRequestsExpired(expired)
    .catch((err) => onError(err, 'Failed to notify expired requests'))
    .finally(() => notifyRequestsUpdated());

  sendExpiryNoticesForRequests(expired.map((request) => request.id)).catch((err) =>
    onError(err, 'Failed to send expiry notices'),
  );
}
