import { formatIstDate } from './date-utils.js';

export const LATE_FEE_PER_DAY = 1;

export const LATE_FEE_CAP_DAYS = 30;
export const LATE_FEE_CAP = LATE_FEE_PER_DAY * LATE_FEE_CAP_DAYS;

export const LATE_FEE_LADDER = [
  { day: 1, variant: 'D1' },
  { day: 3, variant: 'D3' },
  { day: 7, variant: 'D7' },
  { day: 14, variant: 'D14' },
  { day: 30, variant: 'D30' },
] as const;

export const RENEWAL_CHASE_LADDER = [
  { day: 2, variant: 'D2' },
  { day: 7, variant: 'D7' },
] as const;

export interface EmailRequestContext {
  requestId: string;
  projectTitle: string;
  returnDueAt: string | null;
  renewalRequestedAt?: string | null;
  studentName: string | null;
  studentEmail: string;
  facultyName: string | null;
  facultyEmail: string;
  items: { name: string; quantity: number }[];
}

export interface ShapedRequestLike {
  id: string;
  projectTitle: string;
  returnDueAt: string | null;
  renewalRequestedAt?: string | null;
  user: { id?: string; name: string | null; email: string } | null;
  targetFaculty: { id?: string; name: string | null; email: string } | null;
  items?: { quantity: number; component: { name: string } | null }[] | null;
}

export function buildEmailContext(shaped: ShapedRequestLike): EmailRequestContext {
  return {
    requestId: shaped.id,
    projectTitle: shaped.projectTitle,
    returnDueAt: shaped.returnDueAt,
    renewalRequestedAt: shaped.renewalRequestedAt ?? null,
    studentName: shaped.user?.name ?? null,
    studentEmail: shaped.user?.email ?? '',
    facultyName: shaped.targetFaculty?.name ?? null,
    facultyEmail: shaped.targetFaculty?.email ?? '',
    items: (shaped.items ?? []).flatMap(({ component, quantity }) =>
      component?.name != null ? [{ name: component.name, quantity }] : [],
    ),
  };
}

function personName(name: string | null, email: string): string {
  return name?.trim() || email;
}

function itemSummary(items: EmailRequestContext['items']): string {
  if (!items.length) return 'the requested components';
  return items.map((item) => `${item.name} x ${item.quantity}`).join(', ');
}

function signOff(): string {
  return 'IIITNR Inventory Mgmt System';
}

export function renewalChaseEmail(
  ctx: EmailRequestContext,
  daysSinceRequest: number,
): { subject: string; text: string } {
  const subject = `Action needed: renewal request for "${ctx.projectTitle}" is still pending`;
  const text = `Dear ${personName(ctx.facultyName, ctx.facultyEmail)},

${personName(ctx.studentName, ctx.studentEmail)} submitted a renewal request for "${ctx.projectTitle}" ${daysSinceRequest} days ago and it is still awaiting your approval.

The return due date was ${ctx.returnDueAt ? formatIstDate(ctx.returnDueAt) : 'recently'}. Until the renewal is approved the request stays on its original deadline, and the items can be written off the inventory if it passes.

Please approve or reject the renewal request in the app.

Regards,
${signOff()}`;

  return { subject, text };
}

export function expiryNoticeForStudent(ctx: EmailRequestContext): {
  subject: string;
  text: string;
} {
  const subject = `Your request "${ctx.projectTitle}" has expired`;
  const text = `Hi ${personName(ctx.studentName, ctx.studentEmail)},

Your request "${ctx.projectTitle}" was due for return on ${ctx.returnDueAt ? formatIstDate(ctx.returnDueAt) : 'its due date'} and has now expired. The items (${itemSummary(ctx.items)}) have been written off the lab inventory.

A late return charge of ₹${LATE_FEE_PER_DAY} per day applies from tomorrow, and it keeps growing until the items are returned. Please return them to the lab as soon as possible — the charge stops as soon as everything is back.

If you still need the items, ask your faculty supervisor to approve a renewal.

Regards,
${signOff()}`;

  return { subject, text };
}

export function expiryNoticeForFaculty(ctx: EmailRequestContext): {
  subject: string;
  text: string;
} {
  const subject = `Request expired: "${ctx.projectTitle}"`;
  const ignoredRenewal = ctx.renewalRequestedAt
    ? `
A renewal was requested on ${formatIstDate(ctx.renewalRequestedAt)} and was not approved in time, so the request fell due on its original deadline.
`
    : '';

  const text = `Dear ${personName(ctx.facultyName, ctx.facultyEmail)},

The request "${ctx.projectTitle}" submitted by ${personName(ctx.studentName, ctx.studentEmail)} was due for return on ${ctx.returnDueAt ? formatIstDate(ctx.returnDueAt) : 'its due date'} and has expired.
${ignoredRenewal}
The following items have been written off the lab inventory:
${itemSummary(ctx.items)}

The student has been notified. Please follow up with them if the items are still needed for the project.

Regards,
${signOff()}`;

  return { subject, text };
}

export function expiryNoticeForStaff(ctx: EmailRequestContext): { subject: string; text: string } {
  const subject = `Inventory write-off: "${ctx.projectTitle}"`;
  const text = `Hi,

A request has expired and stock has been written off the inventory.

Request: "${ctx.projectTitle}"
Student: ${personName(ctx.studentName, ctx.studentEmail)}
Supervisor: ${personName(ctx.facultyName, ctx.facultyEmail)}
Was due: ${ctx.returnDueAt ? formatIstDate(ctx.returnDueAt) : 'unknown'}

Items written off: ${itemSummary(ctx.items)}

The student and the supervisor have been notified. Please check the stock levels if these components are needed for upcoming labs.

Regards,
${signOff()}`;

  return { subject, text };
}

export function lateFeeEmail(
  ctx: EmailRequestContext,
  daysOverdue: number,
  feeAccrued: number,
  variant: string,
): { subject: string; text: string } {
  const subject = `Overdue: ₹${feeAccrued} accrued on "${ctx.projectTitle}"`;

  const charge = `A late return charge of ₹${LATE_FEE_PER_DAY} per day applies. ₹${feeAccrued} has accrued so far (${daysOverdue} day${daysOverdue === 1 ? '' : 's'} overdue), and the charge is capped at ₹${LATE_FEE_CAP}.`;

  const opening = `Hi ${personName(ctx.studentName, ctx.studentEmail)},

Your request "${ctx.projectTitle}" was due for return on ${ctx.returnDueAt ? formatIstDate(ctx.returnDueAt) : 'its due date'} and is now ${daysOverdue} day${daysOverdue === 1 ? '' : 's'} overdue.

${charge}`;

  const bodies: Record<string, string> = {
    D1: `${opening}

The items (${itemSummary(ctx.items)}) are still outstanding. Please return them to the lab — the charge stops as soon as everything is back.`,

    D3: `${opening}

The items (${itemSummary(ctx.items)}) have now been written off the lab inventory. Please return them to the lab so they can be put back into stock.`,

    D7: `${opening}

The items (${itemSummary(ctx.items)}) have been written off the lab inventory and the overdue period keeps being charged. Please return them to the lab, or talk to your supervisor if you still need them.`,

    D14: `${opening}

The items (${itemSummary(ctx.items)}) have been written off the lab inventory and the charge is now at its cap of ₹${LATE_FEE_CAP}. Please return them to the lab, or talk to your supervisor if you still need them.`,

    D30: `${opening}

The items (${itemSummary(ctx.items)}) have been written off the lab inventory and the charge has reached its cap of ₹${LATE_FEE_CAP}. Please return them to the lab as soon as possible.`,
  };

  const text =
    bodies[variant] ??
    `${opening}\n\nPlease return the items (${itemSummary(ctx.items)}) to the lab.`;

  return { subject, text };
}
