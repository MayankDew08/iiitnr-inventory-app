import './test-setup.js';

import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, test } from 'bun:test';
import { eq } from 'drizzle-orm';
import { db } from '../src/drizzle/db.js';
import { request, requestEmail, component } from '../src/drizzle/schema.js';
import { setMailSender } from '../src/services/email-service.js';
import { IST_DAY_MS, istDayStart, istDaysBetween } from '../src/services/date-utils.js';
import {
  computeLateFee,
  findLateFeeCandidates,
  runLateFeeSweep,
} from '../src/services/late-fee.js';
import { findRenewalChaseCandidates, runRenewalChaseSweep } from '../src/services/renewal-chase.js';
import { sendExpiryNotices } from '../src/services/expiry-notice.js';
import { createComponent, createRequest, createUser, deleteAllRequests } from './helpers.js';

interface SentEmail {
  to: string;
  subject: string;
  text: string;
}

let sentEmails: SentEmail[] = [];

function istNoonIso(offsetDays: number): string {
  return new Date(
    istDayStart(new Date()).getTime() + offsetDays * IST_DAY_MS + 12 * 60 * 60 * 1000,
  ).toISOString();
}

function daysAgoIso(days: number): string {
  return istNoonIso(-days);
}

async function emailsFor(requestId: string) {
  return db.select().from(requestEmail).where(eq(requestEmail.requestId, requestId));
}

let studentId: string;
let facultyId: string;
let adminId: string;
let componentId: string;

beforeAll(async () => {
  const student = await createUser({
    email: 'student-fee@example.com',
    name: 'Fee Student',
    role: 'STUDENT',
  });
  const faculty = await createUser({
    email: 'faculty-fee@example.com',
    name: 'Fee Faculty',
    role: 'FACULTY',
  });
  const admin = await createUser({
    email: 'admin-fee@example.com',
    name: 'Fee Admin',
    role: 'ADMIN',
  });

  studentId = student.id;
  facultyId = faculty.id;
  adminId = admin.id;
});

afterAll(async () => {
  setMailSender(null);
  delete process.env.MAIL_ENABLED;
  await db.delete(requestEmail);
  await deleteAllRequests();
  await db.delete(component).where(eq(component.name, 'Fee Test Sensor'));
});

beforeEach(async () => {
  sentEmails = [];
  // The sweeps only send when mail is explicitly enabled.
  process.env.MAIL_ENABLED = 'true';
  setMailSender(async (to, subject, text) => {
    sentEmails.push({ to, subject, text });
    return true;
  });

  // A fresh component per test — it is removed again in the cleanup below.
  const comp = await createComponent({ name: 'Fee Test Sensor', totalQuantity: 10 });
  componentId = comp.id;
});

afterEach(async () => {
  await db.delete(requestEmail);
  await deleteAllRequests();
  await db.delete(component).where(eq(component.id, componentId));
});

describe('istDaysBetween', () => {
  test('counts whole IST calendar days', () => {
    const sameDay = new Date('2026-10-21T04:00:00.000Z'); // 09:30 IST
    const laterSameDay = new Date('2026-10-21T12:00:00.000Z'); // 17:30 IST
    const nextDay = new Date('2026-10-22T04:00:00.000Z'); // 09:30 IST next day

    expect(istDaysBetween(sameDay, laterSameDay)).toBe(0);
    expect(istDaysBetween(sameDay, nextDay)).toBe(1);
  });

  test('treats the IST midnight boundary as a new day', () => {
    const justBeforeMidnight = new Date('2026-10-21T18:29:00.000Z'); // 23:59 IST
    const justAfterMidnight = new Date('2026-10-21T18:31:00.000Z'); // 00:01 IST

    expect(istDaysBetween(justBeforeMidnight, justAfterMidnight)).toBe(1);
  });
});

describe('computeLateFee', () => {
  test('charges one rupee per day overdue', () => {
    expect(computeLateFee(0)).toBe(0);
    expect(computeLateFee(1)).toBe(1);
    expect(computeLateFee(7)).toBe(7);
  });

  test('caps the charge at one return period', () => {
    expect(computeLateFee(30)).toBe(30);
    expect(computeLateFee(45)).toBe(30);
    expect(computeLateFee(365)).toBe(30);
  });
});

describe('late fee sweep', () => {
  test('emails the student once per ladder rung and records the charge', async () => {
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Overdue project',
      status: 'EXPIRED',
      items: [{ componentId, quantity: 1, fulfilledQuantity: 1 }],
    });

    await db
      .update(request)
      .set({ returnDueAt: daysAgoIso(2), returnedAt: null, lateFeeAccrued: 0 })
      .where(eq(request.id, req.id));

    const candidates = await findLateFeeCandidates();
    expect(candidates).toHaveLength(1);
    expect(candidates[0]?.daysOverdue).toBe(2);
    expect(candidates[0]?.variant).toBe('D1');
    expect(candidates[0]?.feeAccrued).toBe(2);
    expect(candidates[0]?.recipientId).toBe(studentId);

    const result = await runLateFeeSweep();
    expect(result.sent).toBe(1);
    expect(sentEmails).toHaveLength(1);
    expect(sentEmails[0]?.to).toBe('student-fee@example.com');
    expect(sentEmails[0]?.text).toContain('₹2');

    const [stored] = await db.select().from(request).where(eq(request.id, req.id));
    expect(stored?.lateFeeAccrued).toBe(2);

    const logged = await emailsFor(req.id);
    expect(logged).toHaveLength(1);
    expect(logged[0]?.kind).toBe('LATE_FEE');
    expect(logged[0]?.variant).toBe('D1');
  });

  test('does not email twice for the same rung', async () => {
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Overdue project',
      status: 'EXPIRED',
      items: [{ componentId, quantity: 1, fulfilledQuantity: 1 }],
    });

    await db
      .update(request)
      .set({ returnDueAt: daysAgoIso(2), returnedAt: null })
      .where(eq(request.id, req.id));

    await runLateFeeSweep();
    const second = await runLateFeeSweep();

    expect(second.sent).toBe(0);
    expect(sentEmails).toHaveLength(1);
  });

  test('jumps to the highest due rung when the backend was down', async () => {
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Overdue project',
      status: 'EXPIRED',
      items: [{ componentId, quantity: 1, fulfilledQuantity: 1 }],
    });

    // Nine days overdue: D1, D3 and D7 are all due, only D7 should be sent.
    await db
      .update(request)
      .set({ returnDueAt: daysAgoIso(9), returnedAt: null })
      .where(eq(request.id, req.id));

    const result = await runLateFeeSweep();
    expect(result.sent).toBe(1);

    const logged = await emailsFor(req.id);
    expect(logged).toHaveLength(1);
    expect(logged[0]?.variant).toBe('D7');
    expect(sentEmails[0]?.text).toContain('₹9');
  });

  test('skips requests that have been returned', async () => {
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Returned late',
      status: 'EXPIRED',
      items: [{ componentId, quantity: 1, fulfilledQuantity: 1, returnedQuantity: 1 }],
    });

    await db
      .update(request)
      .set({ returnDueAt: daysAgoIso(5), returnedAt: daysAgoIso(1) })
      .where(eq(request.id, req.id));

    const candidates = await findLateFeeCandidates();
    expect(candidates).toHaveLength(0);

    const result = await runLateFeeSweep();
    expect(result.sent).toBe(0);
    expect(sentEmails).toHaveLength(0);
  });

  test('retries when the send fails', async () => {
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Flaky SMTP',
      status: 'EXPIRED',
      items: [{ componentId, quantity: 1, fulfilledQuantity: 1 }],
    });

    await db
      .update(request)
      .set({ returnDueAt: daysAgoIso(2), returnedAt: null })
      .where(eq(request.id, req.id));

    let attempts = 0;
    setMailSender(async () => {
      attempts++;
      return attempts > 1;
    });

    const first = await runLateFeeSweep();
    expect(first.sent).toBe(0);
    expect(first.failed).toBe(1);
    // Nothing is recorded, so the next sweep tries again.
    expect(await emailsFor(req.id)).toHaveLength(0);

    const second = await runLateFeeSweep();
    expect(second.sent).toBe(1);
    expect(await emailsFor(req.id)).toHaveLength(1);
  });

  test('does not charge before the first overdue day', async () => {
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Just expired',
      status: 'EXPIRED',
      items: [{ componentId, quantity: 1, fulfilledQuantity: 1 }],
    });

    await db
      .update(request)
      .set({ returnDueAt: daysAgoIso(0), returnedAt: null })
      .where(eq(request.id, req.id));

    const candidates = await findLateFeeCandidates();
    expect(candidates).toHaveLength(0);
  });
});

describe('renewal chase sweep', () => {
  test('chases the nominating faculty member after two days', async () => {
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Renewal project',
      status: 'REQUESTED_RENEW',
      items: [{ componentId, quantity: 1, fulfilledQuantity: 1 }],
    });

    await db
      .update(request)
      .set({ renewalRequestedAt: daysAgoIso(3) })
      .where(eq(request.id, req.id));

    const candidates = await findRenewalChaseCandidates();
    expect(candidates).toHaveLength(1);
    expect(candidates[0]?.recipientId).toBe(facultyId);
    expect(candidates[0]?.variant).toBe('D2');

    const result = await runRenewalChaseSweep();
    expect(result.sent).toBe(1);
    expect(sentEmails).toHaveLength(1);
    expect(sentEmails[0]?.to).toBe('faculty-fee@example.com');

    const logged = await emailsFor(req.id);
    expect(logged).toHaveLength(1);
    expect(logged[0]?.kind).toBe('RENEWAL_CHASE');
    expect(logged[0]?.variant).toBe('D2');
  });

  test('sends a single follow-up after a week', async () => {
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Renewal project',
      status: 'REQUESTED_RENEW',
      items: [{ componentId, quantity: 1, fulfilledQuantity: 1 }],
    });

    await db
      .update(request)
      .set({ renewalRequestedAt: daysAgoIso(8) })
      .where(eq(request.id, req.id));

    const result = await runRenewalChaseSweep();
    expect(result.sent).toBe(1);

    const logged = await emailsFor(req.id);
    expect(logged).toHaveLength(1);
    expect(logged[0]?.variant).toBe('D7');
  });

  test('does not chase a renewal that was requested today', async () => {
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Renewal project',
      status: 'REQUESTED_RENEW',
      items: [{ componentId, quantity: 1, fulfilledQuantity: 1 }],
    });

    await db
      .update(request)
      .set({ renewalRequestedAt: daysAgoIso(0) })
      .where(eq(request.id, req.id));

    const candidates = await findRenewalChaseCandidates();
    expect(candidates).toHaveLength(0);
  });

  test('ignores requests that are not awaiting a renewal decision', async () => {
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Not a renewal',
      status: 'ISSUED',
      items: [{ componentId, quantity: 1, fulfilledQuantity: 1 }],
    });

    await db
      .update(request)
      .set({ renewalRequestedAt: daysAgoIso(5) })
      .where(eq(request.id, req.id));

    const candidates = await findRenewalChaseCandidates();
    expect(candidates).toHaveLength(0);
  });
});

describe('expiry notice', () => {
  test('notifies the student, the supervisor and the staff', async () => {
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Expired project',
      status: 'EXPIRED',
      items: [{ componentId, quantity: 2, fulfilledQuantity: 2 }],
    });

    await db
      .update(request)
      .set({ returnDueAt: daysAgoIso(1), returnedAt: null })
      .where(eq(request.id, req.id));

    const result = await sendExpiryNotices(req.id);
    expect(result.sent).toBe(3);
    expect(sentEmails).toHaveLength(3);

    const recipients = sentEmails.map((mail) => mail.to);
    expect(recipients).toContain('student-fee@example.com');
    expect(recipients).toContain('faculty-fee@example.com');
    expect(recipients).toContain('admin-fee@example.com');
  });

  test('tells the student about the charge but not the faculty or staff', async () => {
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Expired project',
      status: 'EXPIRED',
      items: [{ componentId, quantity: 1, fulfilledQuantity: 1 }],
    });

    await db
      .update(request)
      .set({ returnDueAt: daysAgoIso(1), returnedAt: null })
      .where(eq(request.id, req.id));

    await sendExpiryNotices(req.id);

    const studentMail = sentEmails.find((mail) => mail.to === 'student-fee@example.com');
    const facultyMail = sentEmails.find((mail) => mail.to === 'faculty-fee@example.com');
    const staffMail = sentEmails.find((mail) => mail.to === 'admin-fee@example.com');

    expect(studentMail?.text).toContain('₹1');
    expect(facultyMail?.text).not.toContain('₹');
    expect(facultyMail?.text).not.toContain('charge');
    expect(staffMail?.text).not.toContain('₹');
    expect(staffMail?.text).not.toContain('charge');
  });

  test('mentions a renewal that was requested but never approved', async () => {
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Expired project',
      status: 'EXPIRED',
      items: [{ componentId, quantity: 1, fulfilledQuantity: 1 }],
    });

    await db
      .update(request)
      .set({ returnDueAt: daysAgoIso(1), returnedAt: null, renewalRequestedAt: daysAgoIso(3) })
      .where(eq(request.id, req.id));

    await sendExpiryNotices(req.id);

    const facultyMail = sentEmails.find((mail) => mail.to === 'faculty-fee@example.com');
    expect(facultyMail?.text).toContain('renewal');
    expect(facultyMail?.text).toContain('not approved in time');
  });

  test('does not notify twice', async () => {
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Expired project',
      status: 'EXPIRED',
      items: [{ componentId, quantity: 1, fulfilledQuantity: 1 }],
    });

    await db
      .update(request)
      .set({ returnDueAt: daysAgoIso(1), returnedAt: null })
      .where(eq(request.id, req.id));

    await sendExpiryNotices(req.id);
    const second = await sendExpiryNotices(req.id);

    expect(second.sent).toBe(0);
    expect(sentEmails).toHaveLength(3);
  });
});

describe('mail being switched off', () => {
  test('the fee sweep keeps the charge up to date but sends nothing', async () => {
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Overdue project',
      status: 'EXPIRED',
      items: [{ componentId, quantity: 1, fulfilledQuantity: 1 }],
    });

    await db
      .update(request)
      .set({ returnDueAt: daysAgoIso(2), returnedAt: null, lateFeeAccrued: 0 })
      .where(eq(request.id, req.id));

    process.env.MAIL_ENABLED = 'false';

    const result = await runLateFeeSweep();

    expect(result.skipped).toBe(true);
    expect(result.sent).toBe(0);
    expect(sentEmails).toHaveLength(0);
    // The charge is a record, so it still accrues.
    expect(result.feesUpdated).toBe(1);

    const [stored] = await db.select().from(request).where(eq(request.id, req.id));
    expect(stored?.lateFeeAccrued).toBe(2);
    expect(await emailsFor(req.id)).toHaveLength(0);
  });

  test('the renewal chase sends nothing', async () => {
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Renewal project',
      status: 'REQUESTED_RENEW',
      items: [{ componentId, quantity: 1, fulfilledQuantity: 1 }],
    });

    await db
      .update(request)
      .set({ renewalRequestedAt: daysAgoIso(5) })
      .where(eq(request.id, req.id));

    process.env.MAIL_ENABLED = 'false';

    const result = await runRenewalChaseSweep();
    expect(result.skipped).toBe(true);
    expect(result.sent).toBe(0);
    expect(sentEmails).toHaveLength(0);
  });
});

describe('request fee record', () => {
  test('is exposed on the request row', async () => {
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Fee record',
      status: 'EXPIRED',
      items: [{ componentId, quantity: 1, fulfilledQuantity: 1 }],
    });

    const [row] = await db.select().from(request).where(eq(request.id, req.id));
    expect(row?.lateFeeAccrued).toBe(0);
    expect(adminId).toBeTruthy();
  });
});
