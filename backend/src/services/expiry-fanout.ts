import { notifyRequestsUpdated } from '../utils/events.js';
import { sendExpiryNoticesForRequests } from './expiry-notice.js';
import { notifyRequestsExpired } from './NotificationService.js';
import type { ExpiredRequest } from './request-expiry.js';

export async function fanOutExpiredRequests(
  expired: ExpiredRequest[],
  onError: (err: unknown, message: string) => void,
): Promise<void> {
  if (!expired.length) return;

  try {
    await notifyRequestsExpired(expired);
  } catch (err) {
    onError(err, 'Failed to notify expired requests');
  }
  notifyRequestsUpdated();

  sendExpiryNoticesForRequests(expired.map((request) => request.id)).catch((err) =>
    onError(err, 'Failed to send expiry notices'),
  );
}
