import { sendTrackedEmail, RequestEmailKind } from './email-service.js';
import { isMailEnabled } from './MailService.js';
import {
  buildEmailContext,
  expiryNoticeForFaculty,
  expiryNoticeForStaff,
  expiryNoticeForStudent,
} from './email-templates.js';
import { fetchExactAndShapeRequest } from './RequestService.js';
import { getStaffRecipients } from './NotificationService.js';

export async function sendExpiryNotices(requestId: string): Promise<{
  sent: number;
  failed: number;
}> {
  const shaped = await fetchExactAndShapeRequest(requestId);
  if (!shaped) return { sent: 0, failed: 1 };

  const ctx = buildEmailContext(shaped);
  let sent = 0;
  let failed = 0;

  const staffRows = await getStaffRecipients();

  const student = {
    requestId,
    recipientId: shaped.user?.id,
    to: shaped.user?.email,
    template: expiryNoticeForStudent(ctx),
    variant: 'STUDENT',
  };
  const faculty = {
    requestId,
    recipientId: shaped.targetFaculty?.id,
    to: shaped.targetFaculty?.email,
    template: expiryNoticeForFaculty(ctx),
    variant: 'FACULTY',
  };

  for (const recipient of [student, faculty]) {
    if (!recipient.recipientId || !recipient.to) continue;

    const ok = await sendTrackedEmail({
      requestId: recipient.requestId,
      recipientId: recipient.recipientId,
      kind: RequestEmailKind.EXPIRY_NOTICE,
      variant: recipient.variant,
      to: recipient.to,
      subject: recipient.template.subject,
      text: recipient.template.text,
    });

    if (ok) sent++;
    else failed++;
  }

  const staffTemplate = expiryNoticeForStaff(ctx);
  for (const staff of staffRows) {
    const ok = await sendTrackedEmail({
      requestId,
      recipientId: staff.id,
      kind: RequestEmailKind.EXPIRY_NOTICE,
      variant: 'STAFF',
      to: staff.email,
      subject: staffTemplate.subject,
      text: staffTemplate.text,
    });

    if (ok) sent++;
    else failed++;
  }

  return { sent, failed };
}

export async function sendExpiryNoticesForRequests(requestIds: string[]): Promise<void> {
  if (!requestIds.length) return;
  if (!isMailEnabled()) return;

  for (const requestId of requestIds) {
    try {
      await sendExpiryNotices(requestId);
    } catch (err) {
      console.error('Failed to send expiry notice', { requestId, err });
    }
  }
}
