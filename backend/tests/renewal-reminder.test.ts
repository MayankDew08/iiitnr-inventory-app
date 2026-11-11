import './test-setup.js';

import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, test } from 'bun:test';
import { eq } from 'drizzle-orm';
import { db } from '../src/drizzle/db.js';
import { request, requestEmail, component } from '../src/drizzle/schema.js';
import {
  runRenewalReminderSweep,
  sendRenewalReminders,
  findRenewalReminderCandidates,
} from '../src/services/renewal-reminder.js';
import { setMailSender } from '../src/services/email-service.js';
import { IST_DAY_MS, istDayStart } from '../src/services/date-utils.js';
import { approveRenewRequestTransaction } from '../src/services/RequestService.js';
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

function daysAheadIso(days: number): string {
  return istNoonIso(days);
}

async function emailsFor(requestId: string) {
  return db.select().from(requestEmail).where(eq(requestEmail.requestId, requestId));
}

let studentId: string;
let facultyId: string;
let componentId: string;

beforeAll(async () => {
  const student = await createUser({
    email: 'renewal-student@example.com',
    name: 'Renewal Student',
    role: 'STUDENT',
  });
  const faculty = await createUser({
    email: 'renewal-faculty@example.com',
    name: 'Renewal Faculty',
    role: 'FACULTY',
  });

  studentId = student.id;
  facultyId = faculty.id;
});

afterAll(async () => {
  setMailSender(null);
  delete process.env.MAIL_ENABLED;
  await db.delete(requestEmail);
  await deleteAllRequests();
  await db.delete(component).where(eq(component.name, 'Renewal Test Sensor'));
});

beforeEach(async () => {
  sentEmails = [];
  process.env.MAIL_ENABLED = 'true';
  setMailSender(async (to, subject, text) => {
    sentEmails.push({ to, subject, text });
    return true;
  });

  const comp = await createComponent({
    name: 'Renewal Test Sensor',
    totalQuantity: 10,
  });
  componentId = comp.id;
});

afterEach(async () => {
  await db.delete(requestEmail);
  await deleteAllRequests();
  await db.delete(component).where(eq(component.id, componentId));
});

describe('findRenewalReminderCandidates', () => {
  test('finds requests due for renewal within the seven day window', async () => {
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Renewal candidate',
      status: 'ISSUED',
      items: [{ componentId, quantity: 1, fulfilledQuantity: 1 }],
    });

    await db
      .update(request)
      .set({ returnDueAt: daysAheadIso(5) })
      .where(eq(request.id, req.id));

    const candidates = await findRenewalReminderCandidates();
    expect(candidates).toHaveLength(1);
    expect(candidates[0]?.requestId).toBe(req.id);
    expect(candidates[0]?.userEmail).toBe('renewal-student@example.com');
  });

  test('excludes requests already sent a reminder', async () => {
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Already reminded',
      status: 'ISSUED',
      items: [{ componentId, quantity: 1, fulfilledQuantity: 1 }],
    });

    await db
      .update(request)
      .set({
        returnDueAt: daysAheadIso(3),
        renewalReminderSentAt: daysAgoIso(1),
      })
      .where(eq(request.id, req.id));

    const candidates = await findRenewalReminderCandidates();
    expect(candidates).toHaveLength(0);
  });

  test('excludes requests not in a renewable status', async () => {
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Returned request',
      status: 'RETURNED',
      items: [{ componentId, quantity: 1, fulfilledQuantity: 1 }],
    });

    await db
      .update(request)
      .set({ returnDueAt: daysAheadIso(3) })
      .where(eq(request.id, req.id));

    const candidates = await findRenewalReminderCandidates();
    expect(candidates).toHaveLength(0);
  });

  test('excludes requests whose return date has already passed', async () => {
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Overdue request',
      status: 'ISSUED',
      items: [{ componentId, quantity: 1, fulfilledQuantity: 1 }],
    });

    await db
      .update(request)
      .set({ returnDueAt: daysAgoIso(2) })
      .where(eq(request.id, req.id));

    const candidates = await findRenewalReminderCandidates();
    expect(candidates).toHaveLength(0);
  });

  test('does not pick requests due more than seven days out', async () => {
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Far future request',
      status: 'ISSUED',
      items: [{ componentId, quantity: 1, fulfilledQuantity: 1 }],
    });

    await db
      .update(request)
      .set({ returnDueAt: daysAheadIso(10) })
      .where(eq(request.id, req.id));

    const candidates = await findRenewalReminderCandidates();
    expect(candidates).toHaveLength(0);
  });
});

describe('sendRenewalReminders', () => {
  test('sends the reminder email for each candidate', async () => {
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Test request',
      status: 'ISSUED',
      items: [{ componentId, quantity: 1, fulfilledQuantity: 1 }],
    });

    await db
      .update(request)
      .set({ returnDueAt: daysAheadIso(3) })
      .where(eq(request.id, req.id));

    const candidates = await findRenewalReminderCandidates();
    expect(candidates).toHaveLength(1);

    const result = await sendRenewalReminders(candidates);
    expect(result.sent).toBe(1);
    expect(result.failed).toBe(0);
    expect(sentEmails).toHaveLength(1);
    expect(sentEmails[0]?.to).toBe('renewal-student@example.com');
    expect(sentEmails[0]?.subject).toContain('Reminder: Return due');
  });

  test('returns zero when given an empty list', async () => {
    const result = await sendRenewalReminders([]);
    expect(result.sent).toBe(0);
    expect(result.failed).toBe(0);
  });
});

describe('renewal reminder sweep', () => {
  test('emails the student and records the reminder', async () => {
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Sweep test request',
      status: 'ISSUED',
      items: [{ componentId, quantity: 1, fulfilledQuantity: 1 }],
    });

    await db
      .update(request)
      .set({ returnDueAt: daysAheadIso(3) })
      .where(eq(request.id, req.id));

    const result = await runRenewalReminderSweep();
    expect(result.candidates).toBe(1);
    expect(result.sent).toBe(1);
    expect(result.failed).toBe(0);
    expect(result.skipped).toBe(false);

    expect(sentEmails).toHaveLength(1);
    expect(sentEmails[0]?.to).toBe('renewal-student@example.com');

    const [stored] = await db.select().from(request).where(eq(request.id, req.id));
    expect(stored?.renewalReminderSentAt).toBeTruthy();
  });

  test('does not email twice for the same request', async () => {
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Idempotency request',
      status: 'ISSUED',
      items: [{ componentId, quantity: 1, fulfilledQuantity: 1 }],
    });

    await db
      .update(request)
      .set({ returnDueAt: daysAheadIso(3) })
      .where(eq(request.id, req.id));

    const first = await runRenewalReminderSweep();
    expect(first.sent).toBe(1);

    const second = await runRenewalReminderSweep();
    expect(second.sent).toBe(0);
    expect(sentEmails).toHaveLength(1);
  });

  test('mail being switched off sends nothing but keeps state', async () => {
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Mail off request',
      status: 'ISSUED',
      items: [{ componentId, quantity: 1, fulfilledQuantity: 1 }],
    });

    await db
      .update(request)
      .set({ returnDueAt: daysAheadIso(3) })
      .where(eq(request.id, req.id));

    process.env.MAIL_ENABLED = 'false';
    const result = await runRenewalReminderSweep();

    expect(result.skipped).toBe(true);
    expect(result.sent).toBe(0);
    expect(sentEmails).toHaveLength(0);

    // Request is still a candidate since reminder not sent
    const candidates = await findRenewalReminderCandidates();
    expect(candidates).toHaveLength(1);
  });

  test('retries when the send fails', async () => {
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Flaky mail',
      status: 'ISSUED',
      items: [{ componentId, quantity: 1, fulfilledQuantity: 1 }],
    });

    await db
      .update(request)
      .set({ returnDueAt: daysAheadIso(3) })
      .where(eq(request.id, req.id));

    let attempts = 0;
    setMailSender(async () => {
      attempts++;
      return attempts > 1;
    });

    const first = await runRenewalReminderSweep();
    expect(first.sent).toBe(0);
    expect(first.failed).toBe(1);
    // Reminder not marked sent, so candidate still eligible
    expect(await emailsFor(req.id)).toHaveLength(0);

    const second = await runRenewalReminderSweep();
    expect(second.sent).toBe(1);
    expect(second.failed).toBe(0);
    expect(await emailsFor(req.id)).toHaveLength(1);
  });

  test('re-arms after a renewal moves the due date', async () => {
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Renewed reminder',
      status: 'ISSUED',
      items: [{ componentId, quantity: 1, fulfilledQuantity: 1 }],
    });

    await db
      .update(request)
      .set({ returnDueAt: daysAheadIso(3) })
      .where(eq(request.id, req.id));

    expect((await runRenewalReminderSweep()).sent).toBe(1);

    await approveRenewRequestTransaction({ id: req.id });
    await db
      .update(request)
      .set({ returnDueAt: daysAheadIso(4) })
      .where(eq(request.id, req.id));

    const second = await runRenewalReminderSweep();
    expect(second.sent).toBe(1);
    expect(sentEmails).toHaveLength(2);
  });

  test('processes multiple candidates in one sweep', async () => {
    const reqs = await Promise.all([
      createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'First request',
        status: 'ISSUED',
        items: [{ componentId, quantity: 1, fulfilledQuantity: 1 }],
      }),
      createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Second request',
        status: 'PARTIALLY_RETURNED',
        items: [{ componentId, quantity: 2, fulfilledQuantity: 2, returnedQuantity: 1 }],
      }),
    ]);

    await db
      .update(request)
      .set({ returnDueAt: daysAheadIso(2) })
      .where(eq(request.id, reqs[0].id));

    await db
      .update(request)
      .set({ returnDueAt: daysAheadIso(4) })
      .where(eq(request.id, reqs[1].id));

    const result = await runRenewalReminderSweep();
    expect(result.candidates).toBe(2);
    expect(result.sent).toBe(2);
    expect(sentEmails).toHaveLength(2);
  });
});
