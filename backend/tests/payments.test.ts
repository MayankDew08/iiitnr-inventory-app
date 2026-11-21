import './test-setup.js';

import { afterAll, afterEach, beforeAll, beforeEach, describe, test } from 'bun:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { existsSync, readdirSync, unlinkSync } from 'node:fs';
import { join } from 'node:path';
import { and, eq, inArray } from 'drizzle-orm';
import { buildApp } from '../src/app.js';
import { db } from '../src/drizzle/db.js';
import {
  auditLog,
  component,
  notification,
  payment,
  paymentAllocation,
  request,
  requestItem,
} from '../src/drizzle/schema.js';
import { NotificationType } from '../src/services/NotificationService.js';
import { createUser, deleteAllRequests, deleteUsers, UserRole } from './helpers.js';

let app: Awaited<ReturnType<typeof buildApp>>;

let studentToken: string;
let otherStudentToken: string;
let studentId: string;
let otherStudentId: string;
let facultyId: string;
let laToken: string;
let laId: string;

const createdUserIds: string[] = [];

const PROOFS_PAYMENTS_DIR = join(process.cwd(), 'private-uploads', 'payments');

const VALID_PNG = Buffer.from([
  0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
  0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1f, 0x15, 0xc4,
  0x89, 0x00, 0x00, 0x00, 0x0a, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9c, 0x63, 0x00, 0x01, 0x00, 0x00,
  0x05, 0x00, 0x01, 0x0d, 0x0a, 0x2d, 0xb4, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44, 0xae,
  0x42, 0x60, 0x82,
]);

function cleanUploadsDir() {
  if (!existsSync(PROOFS_PAYMENTS_DIR)) return;
  for (const file of readdirSync(PROOFS_PAYMENTS_DIR)) {
    if (file === '.gitkeep') continue;
    try {
      unlinkSync(join(PROOFS_PAYMENTS_DIR, file));
    } catch {
      /* ignore */
    }
  }
}

async function clearPaymentsAndRequests() {
  await db.delete(notification);
  await db.delete(auditLog);
  await db.delete(paymentAllocation);
  await db.delete(payment);
  await deleteAllRequests();
  await db.delete(component);
}

async function seedFine(
  userId: string,
  amount: number,
  options: { returned?: boolean; settled?: boolean; finalized?: boolean } = {},
) {
  const now = new Date().toISOString();
  const returnedAt = options.returned === false ? null : now;
  // `fineFinalizedAt` is stamped by the return transaction whenever a full
  // return freezes a positive fee; `finalized: false` simulates a row whose fee
  // was never frozen, which must never become payable.
  const finalized = returnedAt !== null && options.finalized !== false;
  const [row] = await db
    .insert(request)
    .values({
      id: randomUUID(),
      userId,
      targetFacultyId: facultyId,
      projectTitle: `Fine ${randomUUID().slice(0, 8)}`,
      status: 'RETURNED',
      createdAt: now,
      updatedAt: now,
      returnDueAt: new Date(Date.now() - 20 * 24 * 60 * 60 * 1000).toISOString(),
      returnedAt,
      lateFeeAccrued: amount,
      fineFinalizedAt: finalized ? returnedAt : null,
      fineSettledAt: options.settled ? now : null,
    })
    .returning();

  return row;
}

function buildMultipartBody(
  parts: Array<{
    name: string;
    value?: string;
    filename?: string;
    contentType?: string;
    bytes?: Uint8Array;
  }>,
) {
  const boundary = `----iiitnr-${randomUUID()}`;
  const chunks: Buffer[] = [];

  for (const part of parts) {
    if (part.bytes) {
      chunks.push(
        Buffer.from(
          `--${boundary}\r\nContent-Disposition: form-data; name="${part.name}"; filename="${part.filename}"\r\nContent-Type: ${part.contentType}\r\n\r\n`,
        ),
      );
      chunks.push(Buffer.from(part.bytes));
      chunks.push(Buffer.from('\r\n'));
    } else {
      chunks.push(
        Buffer.from(
          `--${boundary}\r\nContent-Disposition: form-data; name="${part.name}"\r\n\r\n${part.value ?? ''}\r\n`,
        ),
      );
    }
  }

  chunks.push(Buffer.from(`--${boundary}--\r\n`));

  return {
    body: Buffer.concat(chunks),
    contentType: `multipart/form-data; boundary=${boundary}`,
  };
}

function proofPart(bytes: Uint8Array = VALID_PNG) {
  return {
    name: 'proof',
    filename: 'proof.png',
    contentType: 'image/png',
    bytes,
  };
}

function createIntent(token: string, payload?: unknown) {
  return app.inject({
    method: 'POST',
    url: '/payments',
    headers: { authorization: `Bearer ${token}` },
    payload: payload as Record<string, unknown> | undefined,
  });
}

function getMine(token: string) {
  return app.inject({
    method: 'GET',
    url: '/payments/mine',
    headers: { authorization: `Bearer ${token}` },
  });
}

function submit(token: string, paymentId: string, parts: Parameters<typeof buildMultipartBody>[0]) {
  const form = buildMultipartBody(parts);
  return app.inject({
    method: 'POST',
    url: `/payments/${paymentId}/submit`,
    headers: {
      authorization: `Bearer ${token}`,
      'content-type': form.contentType,
    },
    payload: form.body,
  });
}

async function getPaymentRow(id: string) {
  const [row] = await db.select().from(payment).where(eq(payment.id, id)).limit(1);
  return row ?? null;
}

async function getRequestRow(id: string) {
  const [row] = await db.select().from(request).where(eq(request.id, id)).limit(1);
  return row ?? null;
}

function getAdminPending(token: string) {
  return app.inject({
    method: 'GET',
    url: '/payments/admin/pending',
    headers: { authorization: `Bearer ${token}` },
  });
}

function getAdminDetail(token: string, paymentId: string) {
  return app.inject({
    method: 'GET',
    url: `/payments/admin/${paymentId}`,
    headers: { authorization: `Bearer ${token}` },
  });
}

function verify(token: string, paymentId: string) {
  return app.inject({
    method: 'POST',
    url: `/payments/admin/${paymentId}/verify`,
    headers: { authorization: `Bearer ${token}` },
  });
}

function reject(
  token: string,
  paymentId: string,
  payload: { reasonCode: unknown; notes?: unknown },
) {
  return app.inject({
    method: 'POST',
    url: `/payments/admin/${paymentId}/reject`,
    headers: { authorization: `Bearer ${token}` },
    payload,
  });
}

/** Seeds a real intent + UTR submission so allocations are present. */
async function seedSubmittedPayment(amounts: number[], utr = '100000000001') {
  const fines = [];
  for (const amount of amounts) {
    fines.push(await seedFine(studentId, amount));
  }
  const intent = await createIntent(studentToken);
  assert.equal(intent.statusCode, 200);
  const paymentId = intent.json().payment.id as string;
  const submitted = await submit(studentToken, paymentId, [
    { name: 'utr', value: utr },
    proofPart(),
  ]);
  assert.equal(submitted.statusCode, 200);
  return { paymentId, fines, submittedAt: submitted.json().payment.submittedAt as string };
}

/**
 * Inserts a SUBMITTED row directly, bypassing the one-active-per-student
 * API guard. For tests that need legacy rows the API can no longer create
 * (e.g. queue ordering with two submitted payments for one student).
 */
async function seedSubmittedRowDirect(amount: number, utr: string, userId: string = studentId) {
  const nowIso = new Date().toISOString();
  const paymentId = randomUUID();
  await db.insert(payment).values({
    id: paymentId,
    userId,
    amount,
    status: 'SUBMITTED',
    utr,
    createdAt: nowIso,
    submittedAt: nowIso,
  });
  return { paymentId, submittedAt: nowIso };
}

async function seedPaymentWithStatus(status: 'PENDING' | 'VERIFIED' | 'REJECTED') {
  const nowIso = new Date().toISOString();
  const [row] = await db
    .insert(payment)
    .values({
      id: randomUUID(),
      userId: studentId,
      amount: 20,
      status,
      createdAt: nowIso,
      submittedAt: status === 'PENDING' ? null : nowIso,
      verifiedAt: status === 'VERIFIED' ? nowIso : null,
      rejectedAt: status === 'REJECTED' ? nowIso : null,
    })
    .returning();
  return row;
}

async function seedRequestItem(requestId: string, componentName: string, quantity: number) {
  const nowIso = new Date().toISOString();
  const [createdComponent] = await db
    .insert(component)
    .values({
      id: randomUUID(),
      name: componentName,
      totalQuantity: quantity,
      availableQuantity: quantity,
      createdAt: nowIso,
      updatedAt: nowIso,
    })
    .returning();
  await db.insert(requestItem).values({
    id: randomUUID(),
    requestId,
    componentId: createdComponent.id,
    quantity,
    fulfilledQuantity: quantity,
    returnedQuantity: 0,
    createdAt: nowIso,
    updatedAt: nowIso,
  });
}

beforeAll(async () => {
  app = await buildApp();

  const suffix = randomUUID();
  const { hash } = await import('bcryptjs');
  const passwordHash = await hash('password123', 12);

  const student = await createUser({
    email: `pay_student_${suffix}@example.com`,
    passwordHash,
    name: 'Paying Student',
    role: UserRole.STUDENT,
  });
  studentId = student.id;
  studentToken = app.jwt.sign({ sub: student.id, role: student.role }, { expiresIn: '1h' });

  const otherStudent = await createUser({
    email: `pay_other_${suffix}@example.com`,
    passwordHash,
    name: 'Other Student',
    role: UserRole.STUDENT,
  });
  otherStudentId = otherStudent.id;
  otherStudentToken = app.jwt.sign(
    { sub: otherStudent.id, role: otherStudent.role },
    { expiresIn: '1h' },
  );

  const faculty = await createUser({
    email: `pay_faculty_${suffix}@example.com`,
    passwordHash,
    name: 'Fine Faculty',
    role: UserRole.FACULTY,
  });
  facultyId = faculty.id;

  const la = await createUser({
    email: `pay_la_${suffix}@example.com`,
    passwordHash,
    name: 'Lab Assistant',
    role: UserRole.LA,
  });
  laId = la.id;
  laToken = app.jwt.sign({ sub: la.id, role: la.role }, { expiresIn: '1h' });

  createdUserIds.push(student.id, otherStudent.id, faculty.id, la.id);
});

afterEach(() => {
  cleanUploadsDir();
});

afterAll(async () => {
  cleanUploadsDir();
  await clearPaymentsAndRequests();
  await deleteUsers(createdUserIds);
  await app.close();
});

beforeEach(async () => {
  cleanUploadsDir();
  await clearPaymentsAndRequests();
});

describe('POST /payments', () => {
  test('creates a payment with the correct total and one allocation per unpaid finalized fine', async () => {
    const fines = [
      await seedFine(studentId, 5),
      await seedFine(studentId, 10),
      await seedFine(studentId, 30),
    ];

    const response = await createIntent(studentToken);
    assert.equal(response.statusCode, 200);

    const body = response.json();
    assert.equal(body.payment.amount, 45);
    assert.equal(body.payment.status, 'PENDING');
    assert.equal(body.payment.allocations.length, 3);
    assert.equal(
      body.payment.allocations.reduce(
        (sum: number, allocation: { amount: number }) => sum + allocation.amount,
        0,
      ),
      45,
    );

    const allocatedRequestIds = body.payment.allocations
      .map((allocation: { requestId: string }) => allocation.requestId)
      .sort();
    assert.deepEqual(allocatedRequestIds, fines.map((fine) => fine.id).sort());

    assert.equal(body.upi.reference, body.payment.id.replaceAll('-', ''));
    assert.ok(
      body.upi.reference.length <= 35,
      `tr must fit NPCI's 35-char limit, got ${body.upi.reference.length}`,
    );
    assert.match(body.upi.reference, /^[A-Za-z0-9]+$/, 'tr must be alphanumeric');
    assert.equal(body.upi.upiVpa, process.env.UPI_VPA);
    assert.equal(body.upi.payeeName, process.env.UPI_PAYEE_NAME);

    const stored = await getPaymentRow(body.payment.id);
    assert.ok(stored);
    assert.equal(stored.amount, 45);
  });

  test('returns 400 if the student has no unpaid finalized fines', async () => {
    const response = await createIntent(studentToken);

    assert.equal(response.statusCode, 400);
    assert.equal(response.json().code, 'NO_UNPAID_FINES');
    assert.equal((await db.select().from(payment)).length, 0);
  });

  test('ignores already-settled fines', async () => {
    await seedFine(studentId, 40, { settled: true });
    await seedFine(studentId, 7);

    const response = await createIntent(studentToken);
    assert.equal(response.statusCode, 200);

    const body = response.json();
    assert.equal(body.payment.amount, 7);
    assert.equal(body.payment.allocations.length, 1);
  });

  test('ignores fines that are finalized but not returned', async () => {
    await seedFine(studentId, 25, { returned: false });
    await seedFine(studentId, 5);

    const response = await createIntent(studentToken);
    assert.equal(response.statusCode, 200);

    const body = response.json();
    assert.equal(body.payment.amount, 5);
    assert.equal(body.payment.allocations.length, 1);
  });

  test('ignores a returned fine whose fee was never frozen', async () => {
    // Only `fineFinalizedAt` marks a bill: a returned row with an amount but no
    // freeze marker (mid-accrual, or a stale swept value) is not payable.
    await seedFine(studentId, 25, { finalized: false });
    await seedFine(studentId, 5);

    const response = await createIntent(studentToken);
    assert.equal(response.statusCode, 200);

    const body = response.json();
    assert.equal(body.payment.amount, 5);
    assert.equal(body.payment.allocations.length, 1);
  });

  test('a returned fine with no frozen marker alone yields NO_UNPAID_FINES', async () => {
    await seedFine(studentId, 25, { finalized: false });

    const response = await createIntent(studentToken);
    assert.equal(response.statusCode, 400);
    assert.equal(response.json().code, 'NO_UNPAID_FINES');
  });

  test('the amount from the client body is ignored', async () => {
    await seedFine(studentId, 12);

    const response = await createIntent(studentToken, { amount: 1 });
    assert.equal(response.statusCode, 200);

    const body = response.json();
    assert.equal(body.payment.amount, 12);
    assert.notEqual(body.payment.amount, 1);
  });

  test('allocation amounts sum exactly to Payment.amount', async () => {
    await seedFine(studentId, 3);
    await seedFine(studentId, 7);
    await seedFine(studentId, 11);

    const response = await createIntent(studentToken);
    assert.equal(response.statusCode, 200);

    const body = response.json();
    const allocationTotal = body.payment.allocations.reduce(
      (sum: number, allocation: { amount: number }) => sum + allocation.amount,
      0,
    );
    assert.equal(allocationTotal, body.payment.amount);
    assert.equal(allocationTotal, 21);
  });

  test('calling POST /payments twice returns the same PENDING payment (idempotent)', async () => {
    await seedFine(studentId, 9);

    const first = await createIntent(studentToken);
    const second = await createIntent(studentToken);

    assert.equal(first.statusCode, 200);
    assert.equal(second.statusCode, 200);

    const firstBody = first.json();
    const secondBody = second.json();
    assert.equal(secondBody.payment.id, firstBody.payment.id);

    const stored = await db.select().from(payment).where(eq(payment.userId, studentId));
    assert.equal(stored.length, 1);
    assert.equal(stored[0]?.status, 'PENDING');

    const allocations = await db
      .select()
      .from(paymentAllocation)
      .where(eq(paymentAllocation.paymentId, firstBody.payment.id));
    assert.equal(allocations.length, 1);
  });

  test('idempotent create: new fine finalized between tap and re-tap refreshes the payment', async () => {
    await seedFine(studentId, 10);

    const first = await createIntent(studentToken);
    assert.equal(first.statusCode, 200);
    const firstId = first.json().payment.id as string;

    await seedFine(studentId, 20);

    const second = await createIntent(studentToken);
    assert.equal(second.statusCode, 200);
    const body = second.json();

    assert.equal(body.payment.id, firstId);
    assert.equal(body.payment.amount, 30);
    assert.equal(body.payment.allocations.length, 2);

    const stored = await db.select().from(payment).where(eq(payment.userId, studentId));
    assert.equal(stored.length, 1);
    assert.equal(stored[0]?.amount, 30);

    const allocations = await db
      .select()
      .from(paymentAllocation)
      .where(eq(paymentAllocation.paymentId, firstId));
    assert.equal(allocations.length, 2);
    assert.equal(
      allocations.reduce((sum, allocation) => sum + allocation.amount, 0),
      30,
    );
  });

  test('idempotent create: no new fines returns the existing payment unchanged', async () => {
    const fine = await seedFine(studentId, 10);

    const first = await createIntent(studentToken);
    const second = await createIntent(studentToken);

    assert.equal(first.statusCode, 200);
    assert.equal(second.statusCode, 200);

    const firstBody = first.json();
    const secondBody = second.json();
    assert.equal(secondBody.payment.id, firstBody.payment.id);
    assert.equal(secondBody.payment.amount, 10);
    assert.equal(secondBody.payment.allocations.length, 1);
    assert.equal(secondBody.payment.allocations[0]?.requestId, fine.id);

    const allocations = await db
      .select()
      .from(paymentAllocation)
      .where(eq(paymentAllocation.paymentId, firstBody.payment.id));
    assert.equal(allocations.length, 1);
  });

  test('idempotent create: all allocated fines disappear deletes the PENDING and returns 400', async () => {
    const fine = await seedFine(studentId, 10);

    const first = await createIntent(studentToken);
    assert.equal(first.statusCode, 200);
    const paymentId = first.json().payment.id as string;

    await db
      .update(request)
      .set({ fineSettledAt: new Date().toISOString() })
      .where(eq(request.id, fine.id));

    const second = await createIntent(studentToken);
    assert.equal(second.statusCode, 400);
    assert.equal(second.json().code, 'NO_UNPAID_FINES');

    assert.equal(await getPaymentRow(paymentId), null);
    const stored = await db.select().from(payment).where(eq(payment.userId, studentId));
    assert.equal(stored.length, 0);
    const allocations = await db
      .select()
      .from(paymentAllocation)
      .where(eq(paymentAllocation.paymentId, paymentId));
    assert.equal(allocations.length, 0);
  });
});

describe('POST /payments/:id/submit', () => {
  test('a student can submit UTR + proof for their own PENDING payment', async () => {
    const fine = await seedFine(studentId, 20);
    const intent = await createIntent(studentToken);
    const paymentId = intent.json().payment.id as string;

    const response = await submit(studentToken, paymentId, [
      { name: 'utr', value: '  123456789012  ' },
      proofPart(),
    ]);

    assert.equal(response.statusCode, 200);
    const submitted = response.json().payment;
    assert.equal(submitted.status, 'SUBMITTED');
    assert.equal(submitted.utr, '123456789012');
    assert.ok(submitted.submittedAt);
    // The API exposes the authenticated proof route, never the file on disk.
    assert.equal(submitted.proofImageUrl, `/payments/${paymentId}/proof`);

    const stored = await getPaymentRow(paymentId);
    assert.ok(stored);
    assert.equal(stored.status, 'SUBMITTED');
    assert.equal(stored.utr, '123456789012');
    assert.ok(stored.proofFileName);
    assert.ok(!stored.proofFileName.includes('/'));

    assert.ok(existsSync(join(PROOFS_PAYMENTS_DIR, stored.proofFileName)));

    const fineRow = await getRequestRow(fine.id);
    assert.equal(fineRow?.fineSettledAt, null);
  });

  test('submit without a UTR is rejected (400)', async () => {
    await seedFine(studentId, 20);
    const intent = await createIntent(studentToken);
    const paymentId = intent.json().payment.id as string;

    const response = await submit(studentToken, paymentId, [proofPart()]);

    assert.equal(response.statusCode, 400);
    assert.equal(response.json().error, 'utr_required');

    const stored = await getPaymentRow(paymentId);
    assert.equal(stored?.status, 'PENDING');
  });

  test('submit without a proof file is rejected (400)', async () => {
    await seedFine(studentId, 20);
    const intent = await createIntent(studentToken);
    const paymentId = intent.json().payment.id as string;

    const response = await submit(studentToken, paymentId, [
      { name: 'utr', value: '123456789013' },
    ]);

    assert.equal(response.statusCode, 400);
    assert.equal(response.json().error, 'proof_required');

    const stored = await getPaymentRow(paymentId);
    assert.equal(stored?.status, 'PENDING');
  });

  test("a student cannot submit another student's payment (403)", async () => {
    await seedFine(studentId, 20);
    const intent = await createIntent(studentToken);
    const paymentId = intent.json().payment.id as string;

    const response = await submit(otherStudentToken, paymentId, [
      { name: 'utr', value: '123456789014' },
      proofPart(),
    ]);

    assert.equal(response.statusCode, 403);

    const stored = await getPaymentRow(paymentId);
    assert.equal(stored?.status, 'PENDING');
    assert.equal(stored?.utr, null);

    const leftovers = existsSync(PROOFS_PAYMENTS_DIR) ? readdirSync(PROOFS_PAYMENTS_DIR) : [];
    assert.equal(leftovers.length, 0);
  });

  test('submitting an already-SUBMITTED payment is rejected (409)', async () => {
    await seedFine(studentId, 20);
    const intent = await createIntent(studentToken);
    const paymentId = intent.json().payment.id as string;

    const first = await submit(studentToken, paymentId, [
      { name: 'utr', value: '123456789015' },
      proofPart(),
    ]);
    assert.equal(first.statusCode, 200);

    const second = await submit(studentToken, paymentId, [
      { name: 'utr', value: '123456789016' },
      proofPart(),
    ]);

    assert.equal(second.statusCode, 409);
    assert.equal(second.json().code, 'PAYMENT_NOT_PENDING');

    const stored = await getPaymentRow(paymentId);
    assert.equal(stored?.utr, '123456789015');
  });

  test('submitting a VERIFIED or REJECTED payment is rejected (409)', async () => {
    const now = new Date().toISOString();

    for (const status of ['VERIFIED', 'REJECTED'] as const) {
      const [seeded] = await db
        .insert(payment)
        .values({
          id: randomUUID(),
          userId: studentId,
          amount: 20,
          status,
          createdAt: now,
          verifiedAt: status === 'VERIFIED' ? now : null,
          rejectedAt: status === 'REJECTED' ? now : null,
        })
        .returning();

      assert.ok(seeded);

      const response = await submit(studentToken, seeded.id, [
        { name: 'utr', value: '123456789017' },
        proofPart(),
      ]);

      assert.equal(response.statusCode, 409);
      assert.equal(response.json().code, 'PAYMENT_NOT_PENDING');

      const stored = await getPaymentRow(seeded.id);
      assert.equal(stored?.status, status);
    }
  });
});

describe('GET /payments/config', () => {
  test('returns the configured VPA + payee name', async () => {
    const response = await app.inject({
      method: 'GET',
      url: '/payments/config',
      headers: { authorization: `Bearer ${studentToken}` },
    });

    assert.equal(response.statusCode, 200);
    assert.deepEqual(response.json(), {
      upiVpa: process.env.UPI_VPA,
      payeeName: process.env.UPI_PAYEE_NAME,
    });
  });
});

describe('GET /payments/mine', () => {
  test("returns the student's PENDING payment with allocations", async () => {
    const fine = await seedFine(studentId, 10);
    const intent = await createIntent(studentToken);
    const paymentId = intent.json().payment.id as string;

    const response = await getMine(studentToken);
    assert.equal(response.statusCode, 200);

    const payments = response.json().payments;
    assert.equal(payments.length, 1);
    assert.equal(payments[0].id, paymentId);
    assert.equal(payments[0].status, 'PENDING');
    assert.equal(payments[0].amount, 10);
    assert.equal(payments[0].allocations.length, 1);
    assert.equal(payments[0].allocations[0].requestId, fine.id);
  });

  test('returns a SUBMITTED payment with utr, proofImageUrl and submittedAt populated', async () => {
    await seedFine(studentId, 20);
    const intent = await createIntent(studentToken);
    const paymentId = intent.json().payment.id as string;

    const submitted = await submit(studentToken, paymentId, [
      { name: 'utr', value: '123456789018' },
      proofPart(),
    ]);
    assert.equal(submitted.statusCode, 200);

    const response = await getMine(studentToken);
    const payments = response.json().payments;
    assert.equal(payments.length, 1);
    assert.equal(payments[0].status, 'SUBMITTED');
    assert.equal(payments[0].utr, '123456789018');
    assert.ok(payments[0].proofImageUrl.includes('/payments/'));
    assert.ok(payments[0].submittedAt);
  });

  test('returns a REJECTED payment with rejectedAt and rejectionReason populated', async () => {
    const now = new Date().toISOString();
    const [seeded] = await db
      .insert(payment)
      .values({
        id: randomUUID(),
        userId: studentId,
        amount: 15,
        status: 'REJECTED',
        createdAt: now,
        rejectedAt: now,
        rejectionReason: 'UTR mismatch',
      })
      .returning();
    assert.ok(seeded);

    const response = await getMine(studentToken);
    const payments = response.json().payments;
    assert.equal(payments.length, 1);
    assert.equal(payments[0].id, seeded.id);
    assert.equal(payments[0].status, 'REJECTED');
    assert.ok(payments[0].rejectedAt);
    assert.equal(payments[0].rejectionReason, 'UTR mismatch');
    assert.deepEqual(payments[0].allocations, []);
  });

  test('does NOT return VERIFIED payments', async () => {
    const now = new Date().toISOString();
    await db.insert(payment).values({
      id: randomUUID(),
      userId: studentId,
      amount: 25,
      status: 'VERIFIED',
      createdAt: now,
      verifiedAt: now,
    });

    await seedFine(studentId, 10);
    await createIntent(studentToken);

    const response = await getMine(studentToken);
    const payments = response.json().payments;
    assert.equal(payments.length, 1);
    assert.equal(payments[0].status, 'PENDING');
    assert.ok(payments.every((row: { status: string }) => row.status !== 'VERIFIED'));
  });

  test("does NOT return another student's payments", async () => {
    const now = new Date().toISOString();
    await db.insert(payment).values({
      id: randomUUID(),
      userId: otherStudentId,
      amount: 30,
      status: 'PENDING',
      createdAt: now,
    });

    await seedFine(studentId, 10);
    await createIntent(studentToken);

    const response = await getMine(studentToken);
    const payments = response.json().payments;
    assert.equal(payments.length, 1);
    assert.equal(payments[0].amount, 10);
    assert.ok(payments.every((row: { userId?: string }) => row.userId !== otherStudentId));
  });

  test('orders payments newest first', async () => {
    const base = Date.parse('2026-01-01T00:00:00.000Z');
    for (const [amount, offset] of [
      [1, 1000],
      [2, 3000],
      [3, 2000],
    ]) {
      // REJECTED rows: history never counts toward the one-active-per-student
      // rule, so three of them can coexist for ordering checks.
      await db.insert(payment).values({
        id: randomUUID(),
        userId: studentId,
        amount,
        status: 'REJECTED',
        createdAt: new Date(base + offset).toISOString(),
      });
    }

    const response = await getMine(studentToken);
    assert.equal(response.statusCode, 200);
    assert.deepEqual(
      response.json().payments.map((row: { amount: number }) => row.amount),
      [2, 3, 1],
    );
  });
});

describe('payment lifecycle invariants', () => {
  test('creating and submitting a payment does NOT settle any fines', async () => {
    const fine = await seedFine(studentId, 33);

    const intent = await createIntent(studentToken);
    assert.equal(intent.statusCode, 200);

    const afterCreate = await getRequestRow(fine.id);
    assert.equal(afterCreate?.fineSettledAt, null);
    assert.equal(afterCreate?.lateFeeAccrued, 33);

    const paymentId = intent.json().payment.id as string;
    const submitted = await submit(studentToken, paymentId, [
      { name: 'utr', value: '123456789019' },
      proofPart(),
    ]);
    assert.equal(submitted.statusCode, 200);

    const afterSubmit = await getRequestRow(fine.id);
    assert.equal(afterSubmit?.fineSettledAt, null);
    assert.equal(afterSubmit?.lateFeeAccrued, 33);
  });
});

describe('POST /payments/admin/:id/verify', () => {
  test('LA can verify a SUBMITTED payment, flipping it to VERIFIED', async () => {
    const { paymentId } = await seedSubmittedPayment([20]);

    const response = await verify(laToken, paymentId);

    assert.equal(response.statusCode, 200);
    assert.equal(response.json().payment.status, 'VERIFIED');
    assert.equal((await getPaymentRow(paymentId))?.status, 'VERIFIED');
  });

  test('verifying sets fineSettledAt on all allocated requests, atomically', async () => {
    const { paymentId, fines } = await seedSubmittedPayment([5, 10, 30]);

    const response = await verify(laToken, paymentId);

    assert.equal(response.statusCode, 200);
    const settled = await Promise.all(fines.map((fine) => getRequestRow(fine.id)));
    assert.ok(settled.every((row) => row?.fineSettledAt != null));
    const timestamps = settled.map((row) => {
      assert.ok(row, 'expected row to exist');
      assert.ok(row.fineSettledAt, 'expected fineSettledAt to be set');
      return Date.parse(row.fineSettledAt);
    });
    assert.ok(Math.max(...timestamps) - Math.min(...timestamps) <= 1);
    assert.ok(settled.every((row) => row?.updatedAt === row?.fineSettledAt));
  });

  test('verifying stamps verifiedAt and verifiedByUserId', async () => {
    const { paymentId } = await seedSubmittedPayment([15]);

    const response = await verify(laToken, paymentId);

    assert.equal(response.statusCode, 200);
    const stored = await getPaymentRow(paymentId);
    assert.ok(stored?.verifiedAt);
    assert.equal(stored?.verifiedByUserId, laId);
  });

  test('verifying an already-VERIFIED payment returns 409', async () => {
    const seeded = await seedPaymentWithStatus('VERIFIED');

    const response = await verify(laToken, seeded.id);

    assert.equal(response.statusCode, 409);
    assert.equal(response.json().code, 'PAYMENT_NOT_SUBMITTED');
    assert.equal((await getPaymentRow(seeded.id))?.status, 'VERIFIED');
  });

  test('verifying a REJECTED payment returns 409', async () => {
    const seeded = await seedPaymentWithStatus('REJECTED');

    const response = await verify(laToken, seeded.id);

    assert.equal(response.statusCode, 409);
    assert.equal(response.json().code, 'PAYMENT_NOT_SUBMITTED');
  });

  test('verifying a PENDING payment returns 409', async () => {
    const seeded = await seedPaymentWithStatus('PENDING');

    const response = await verify(laToken, seeded.id);

    assert.equal(response.statusCode, 409);
    assert.equal(response.json().code, 'PAYMENT_NOT_SUBMITTED');
  });

  test('verifying a missing payment returns 404', async () => {
    const response = await verify(laToken, randomUUID());

    assert.equal(response.statusCode, 404);
    assert.equal(response.json().code, 'NOT_FOUND');
  });

  test('a student cannot call the verify endpoint (403)', async () => {
    const { paymentId } = await seedSubmittedPayment([10]);

    const response = await verify(studentToken, paymentId);

    assert.equal(response.statusCode, 403);
    assert.equal((await getPaymentRow(paymentId))?.status, 'SUBMITTED');
  });

  test('two concurrent verify calls: one wins, the other gets 409', async () => {
    const { paymentId } = await seedSubmittedPayment([20]);

    const [first, second] = await Promise.all([
      verify(laToken, paymentId),
      verify(laToken, paymentId),
    ]);

    assert.deepEqual([first.statusCode, second.statusCode].sort(), [200, 409]);
    const settled = await db
      .select()
      .from(paymentAllocation)
      .where(eq(paymentAllocation.paymentId, paymentId));
    assert.equal(settled.length, 1);
  });

  test('verify writes an audit log entry', async () => {
    const { paymentId } = await seedSubmittedPayment([20]);

    const response = await verify(laToken, paymentId);

    assert.equal(response.statusCode, 200);
    const logs = await db
      .select()
      .from(auditLog)
      .where(and(eq(auditLog.entityType, 'Payment'), eq(auditLog.entityId, paymentId)));
    assert.equal(logs.length, 1);
    assert.equal(logs[0].action, 'UPDATE');
    assert.equal(logs[0].userId, laId);
    assert.deepEqual(JSON.parse(logs[0].oldValues ?? '{}'), { status: 'SUBMITTED' });
    assert.equal(JSON.parse(logs[0].newValues ?? '{}').status, 'VERIFIED');
  });

  test('verify notifies the student', async () => {
    const { paymentId } = await seedSubmittedPayment([25]);

    const response = await verify(laToken, paymentId);

    assert.equal(response.statusCode, 200);
    const rows = await db
      .select()
      .from(notification)
      .where(eq(notification.recipientId, studentId));
    assert.equal(rows.length, 1);
    assert.equal(rows[0].type, NotificationType.PAYMENT_VERIFIED);
    assert.equal(rows[0].title, 'Payment verified');
    assert.ok(rows[0].body?.includes('₹25'));
  });
});

describe('POST /payments/admin/:id/reject', () => {
  test('LA can reject a SUBMITTED payment with reasonCode', async () => {
    const { paymentId, fines } = await seedSubmittedPayment([20]);

    const response = await reject(laToken, paymentId, { reasonCode: 'UTR_NOT_FOUND' });

    assert.equal(response.statusCode, 200);
    const stored = await getPaymentRow(paymentId);
    assert.equal(stored?.status, 'REJECTED');
    assert.ok(stored?.rejectedAt);
    assert.equal(stored?.rejectionReasonCode, 'UTR_NOT_FOUND');
    assert.equal(stored?.rejectionReason, null);

    for (const fine of fines) {
      assert.equal((await getRequestRow(fine.id))?.fineSettledAt, null);
    }
  });

  test('rejecting does NOT settle any fines', async () => {
    const { paymentId, fines } = await seedSubmittedPayment([5, 15]);

    const response = await reject(laToken, paymentId, { reasonCode: 'AMOUNT_MISMATCH' });

    assert.equal(response.statusCode, 200);
    const settled = await Promise.all(fines.map((fine) => getRequestRow(fine.id)));
    assert.ok(settled.every((row) => row?.fineSettledAt == null));
  });

  test('rejecting without a valid reasonCode returns 400', async () => {
    const { paymentId } = await seedSubmittedPayment([10]);

    for (const payload of [{ reasonCode: 'NOT_A_REASON' }, { reasonCode: 123 }, {}]) {
      const response = await reject(laToken, paymentId, payload);
      assert.equal(response.statusCode, 400);
      assert.equal(response.json().error, 'invalid reasonCode');
    }
    assert.equal((await getPaymentRow(paymentId))?.status, 'SUBMITTED');
  });

  test('rejecting with notes stores the notes', async () => {
    const { paymentId } = await seedSubmittedPayment([10]);

    const response = await reject(laToken, paymentId, {
      reasonCode: 'OTHER',
      notes: '  Bank statement shows a different reference.  ',
    });

    assert.equal(response.statusCode, 200);
    const stored = await getPaymentRow(paymentId);
    assert.equal(stored?.rejectionReason, 'Bank statement shows a different reference.');
  });

  test('blank and overlong notes are handled', async () => {
    const { paymentId } = await seedSubmittedPayment([10]);

    const blank = await reject(laToken, paymentId, { reasonCode: 'OTHER', notes: '   ' });
    assert.equal(blank.statusCode, 200);
    assert.equal((await getPaymentRow(paymentId))?.rejectionReason, null);

    // A second SUBMITTED payment for the same student can no longer be created
    // through the API (one active payment per student), so seed it directly.
    const second = await seedSubmittedRowDirect(10, '100000000102');
    const overlong = await reject(laToken, second.paymentId, {
      reasonCode: 'OTHER',
      notes: 'x'.repeat(501),
    });
    assert.equal(overlong.statusCode, 400);
    assert.equal((await getPaymentRow(second.paymentId))?.status, 'SUBMITTED');
  });

  test('rejecting an already-REJECTED payment returns 409', async () => {
    const seeded = await seedPaymentWithStatus('REJECTED');

    const response = await reject(laToken, seeded.id, { reasonCode: 'PROOF_UNCLEAR' });

    assert.equal(response.statusCode, 409);
    assert.equal(response.json().code, 'PAYMENT_NOT_SUBMITTED');
  });

  test('a student cannot call the reject endpoint (403)', async () => {
    const { paymentId } = await seedSubmittedPayment([10]);

    const response = await reject(studentToken, paymentId, { reasonCode: 'PROOF_UNCLEAR' });

    assert.equal(response.statusCode, 403);
    assert.equal((await getPaymentRow(paymentId))?.status, 'SUBMITTED');
  });

  test('reject writes an audit log entry and notifies the student', async () => {
    const { paymentId } = await seedSubmittedPayment([12]);

    const response = await reject(laToken, paymentId, {
      reasonCode: 'PROOF_UNCLEAR',
      notes: 'Screenshot too blurry',
    });

    assert.equal(response.statusCode, 200);
    const logs = await db
      .select()
      .from(auditLog)
      .where(and(eq(auditLog.entityType, 'Payment'), eq(auditLog.entityId, paymentId)));
    assert.equal(logs.length, 1);
    assert.equal(logs[0].action, 'UPDATE');
    assert.equal(JSON.parse(logs[0].newValues ?? '{}').reasonCode, 'PROOF_UNCLEAR');

    const rows = await db
      .select()
      .from(notification)
      .where(eq(notification.recipientId, studentId));
    assert.equal(rows.length, 1);
    assert.equal(rows[0].type, NotificationType.PAYMENT_REJECTED);
    assert.equal(rows[0].title, 'Payment rejected');
    assert.ok(rows[0].body?.includes('payment proof was unclear'));
    assert.ok(rows[0].body?.includes('Screenshot too blurry'));
  });
});

describe('GET /payments/admin/pending', () => {
  test('returns SUBMITTED payments, oldest-first', async () => {
    // Two students, because Payment_one_active_per_user allows only one
    // PENDING/SUBMITTED payment per student.
    const first = await seedSubmittedRowDirect(10, '100000000201');
    const second = await seedSubmittedRowDirect(20, '100000000202', otherStudentId);

    const now = Date.parse(first.submittedAt);
    await db
      .update(payment)
      .set({ submittedAt: new Date(now - 60_000).toISOString() })
      .where(eq(payment.id, first.paymentId));

    const response = await getAdminPending(laToken);

    assert.equal(response.statusCode, 200);
    const rows = response.json().payments;
    assert.deepEqual(
      rows.map((row: { id: string }) => row.id),
      [first.paymentId, second.paymentId],
    );
    assert.equal(rows[0].userId, studentId);
    assert.equal(rows[0].studentName, 'Paying Student');
    assert.ok(rows[0].studentEmail.startsWith('pay_student_'));
    assert.ok(rows[0].studentEmail.endsWith('@example.com'));
    assert.equal(rows[0].utr, '100000000201');
    assert.equal('proofImageUrl' in rows[0], false);
    assert.equal('allocations' in rows[0], false);
  });

  test('excludes VERIFIED and REJECTED payments', async () => {
    const submitted = await seedSubmittedPayment([10]);
    await seedPaymentWithStatus('VERIFIED');
    await seedPaymentWithStatus('REJECTED');

    const response = await getAdminPending(laToken);

    assert.equal(response.statusCode, 200);
    assert.deepEqual(
      response.json().payments.map((row: { id: string }) => row.id),
      [submitted.paymentId],
    );
  });

  test('a student cannot call the admin pending endpoint (403)', async () => {
    const response = await getAdminPending(studentToken);

    assert.equal(response.statusCode, 403);
  });

  test('returns an empty list when nothing is pending', async () => {
    const response = await getAdminPending(laToken);

    assert.equal(response.statusCode, 200);
    assert.deepEqual(response.json(), { payments: [] });
  });
});

describe('GET /payments/admin/:id', () => {
  test('returns payment with allocations and student info', async () => {
    const { paymentId, fines } = await seedSubmittedPayment([5, 10]);
    await seedRequestItem(fines[0].id, 'Arduino Uno', 2);

    const response = await getAdminDetail(laToken, paymentId);

    assert.equal(response.statusCode, 200);
    const body = response.json();
    assert.equal(body.payment.id, paymentId);
    assert.equal(body.payment.amount, 15);
    assert.equal(body.student.id, studentId);
    assert.equal(body.student.name, 'Paying Student');
    assert.ok(body.student.email.includes('@example.com'));
    assert.equal(body.allocations.length, 2);
    assert.deepEqual(
      body.allocations.map((allocation: { requestId: string }) => allocation.requestId).sort(),
      fines.map((fine) => fine.id).sort(),
    );
    const withItem = body.allocations.find(
      (allocation: { requestId: string }) => allocation.requestId === fines[0].id,
    );
    assert.deepEqual(withItem.items, [{ componentName: 'Arduino Uno', quantity: 2 }]);
    assert.ok(withItem.projectTitle.startsWith('Fine '));
  });

  test('returns 404 for a missing payment', async () => {
    const response = await getAdminDetail(laToken, randomUUID());

    assert.equal(response.statusCode, 404);
    assert.equal(response.json().code, 'NOT_FOUND');
  });

  test('a student cannot call the admin detail endpoint (403)', async () => {
    const { paymentId } = await seedSubmittedPayment([10]);

    const response = await getAdminDetail(studentToken, paymentId);

    assert.equal(response.statusCode, 403);
  });
});

describe('one active payment per student (B1/B2)', () => {
  test('POST /payments while a SUBMITTED payment exists returns 409 PAYMENT_AWAITING_VERIFICATION', async () => {
    const { paymentId } = await seedSubmittedPayment([20]);
    const before = await db.select().from(payment).where(eq(payment.userId, studentId));

    const response = await createIntent(studentToken);

    assert.equal(response.statusCode, 409);
    assert.equal(response.json().code, 'PAYMENT_AWAITING_VERIFICATION');
    const after = await db.select().from(payment).where(eq(payment.userId, studentId));
    assert.equal(after.length, before.length);
    assert.equal((await getPaymentRow(paymentId))?.status, 'SUBMITTED');
  });

  test('two parallel POST /payments calls return the same payment id, with only 1 active row', async () => {
    await seedFine(studentId, 30);

    const [first, second] = await Promise.all([
      createIntent(studentToken),
      createIntent(studentToken),
    ]);

    assert.equal(first.statusCode, 200);
    assert.equal(second.statusCode, 200);
    assert.equal(first.json().payment.id, second.json().payment.id);

    const active = await db
      .select()
      .from(payment)
      .where(and(eq(payment.userId, studentId), inArray(payment.status, ['PENDING', 'SUBMITTED'])));
    assert.equal(active.length, 1);
  });

  test('verify returns 409 when an allocated fine is already settled, and nothing is written', async () => {
    const { paymentId, fines } = await seedSubmittedPayment([10, 20]);
    await db
      .update(request)
      .set({ fineSettledAt: new Date().toISOString() })
      .where(eq(request.id, fines[0].id));

    const response = await verify(laToken, paymentId);

    assert.equal(response.statusCode, 409);
    assert.equal(response.json().code, 'FINE_ALREADY_SETTLED');
    assert.equal((await getPaymentRow(paymentId))?.status, 'SUBMITTED');
    // Atomic: the other allocated fine must not have been settled either.
    assert.equal((await getRequestRow(fines[1].id))?.fineSettledAt, null);
  });
});

describe('UTR validation (S3)', () => {
  test('11 digits and letters are rejected with 400 INVALID_UTR', async () => {
    await seedFine(studentId, 20);
    const intent = await createIntent(studentToken);
    const paymentId = intent.json().payment.id as string;

    for (const bad of ['12345678901', 'abcdefghijkl', 'UTR123456789']) {
      const response = await submit(studentToken, paymentId, [
        { name: 'utr', value: bad },
        proofPart(),
      ]);
      assert.equal(response.statusCode, 400, `utr ${bad}`);
      assert.equal(response.json().code, 'INVALID_UTR');
    }
    assert.equal((await getPaymentRow(paymentId))?.status, 'PENDING');
    assert.equal((await getPaymentRow(paymentId))?.utr, null);
  });

  test('UTR with spaces is accepted and stored without spaces', async () => {
    await seedFine(studentId, 20);
    const intent = await createIntent(studentToken);
    const paymentId = intent.json().payment.id as string;

    const response = await submit(studentToken, paymentId, [
      { name: 'utr', value: '1234 5678 9012' },
      proofPart(),
    ]);

    assert.equal(response.statusCode, 200);
    assert.equal((await getPaymentRow(paymentId))?.utr, '123456789012');
  });

  test('reusing a UTR on a second student while the first is SUBMITTED returns 409 UTR_ALREADY_USED', async () => {
    const utr = '109876543210';
    await seedSubmittedPayment([20], utr);

    await seedFine(otherStudentId, 25);
    const intent = await createIntent(otherStudentToken);
    assert.equal(intent.statusCode, 200);
    const otherPaymentId = intent.json().payment.id as string;

    const response = await submit(otherStudentToken, otherPaymentId, [
      { name: 'utr', value: utr },
      proofPart(),
    ]);

    assert.equal(response.statusCode, 409);
    assert.equal(response.json().code, 'UTR_ALREADY_USED');
    assert.equal((await getPaymentRow(otherPaymentId))?.status, 'PENDING');
  });

  test('reusing a UTR after a PROOF_UNCLEAR rejection is accepted', async () => {
    const utr = '109876543211';
    const { paymentId } = await seedSubmittedPayment([20], utr);
    const rejected = await reject(laToken, paymentId, { reasonCode: 'PROOF_UNCLEAR' });
    assert.equal(rejected.statusCode, 200);

    await seedFine(studentId, 30);
    const intent = await createIntent(studentToken);
    assert.equal(intent.statusCode, 200);
    const freshId = intent.json().payment.id as string;
    assert.notEqual(freshId, paymentId);

    const response = await submit(studentToken, freshId, [
      { name: 'utr', value: utr },
      proofPart(),
    ]);

    assert.equal(response.statusCode, 200);
    assert.equal((await getPaymentRow(freshId))?.utr, utr);
  });

  test('reusing a UTR after a UTR_NOT_FOUND rejection returns 409 UTR_BLOCKED', async () => {
    const utr = '109876543212';
    const { paymentId } = await seedSubmittedPayment([20], utr);
    const rejected = await reject(laToken, paymentId, { reasonCode: 'UTR_NOT_FOUND' });
    assert.equal(rejected.statusCode, 200);

    await seedFine(studentId, 30);
    const intent = await createIntent(studentToken);
    assert.equal(intent.statusCode, 200);
    const freshId = intent.json().payment.id as string;

    const response = await submit(studentToken, freshId, [
      { name: 'utr', value: utr },
      proofPart(),
    ]);

    assert.equal(response.statusCode, 409);
    assert.equal(response.json().code, 'UTR_BLOCKED');
    assert.equal((await getPaymentRow(freshId))?.status, 'PENDING');
  });
});

describe('GET /payments/:id/proof (S1)', () => {
  function getProof(token: string, paymentId: string) {
    return app.inject({
      method: 'GET',
      url: `/payments/${paymentId}/proof`,
      headers: { authorization: `Bearer ${token}` },
    });
  }

  test('owner can fetch their proof as image/webp', async () => {
    const { paymentId } = await seedSubmittedPayment([20]);

    const response = await getProof(studentToken, paymentId);

    assert.equal(response.statusCode, 200);
    assert.match(response.headers['content-type'] ?? '', /image\/webp/);
    assert.equal(response.headers['cache-control'], 'private, no-store');
    assert.ok(response.rawPayload.length > 0);
  });

  test('another student gets 403', async () => {
    const { paymentId } = await seedSubmittedPayment([20]);

    const response = await getProof(otherStudentToken, paymentId);

    assert.equal(response.statusCode, 403);
  });

  test('LA can fetch a student proof', async () => {
    const { paymentId } = await seedSubmittedPayment([20]);

    const response = await getProof(laToken, paymentId);

    assert.equal(response.statusCode, 200);
    assert.match(response.headers['content-type'] ?? '', /image\/webp/);
  });

  test('the old public path /api/uploads/payments/<file> is no longer served', async () => {
    const { paymentId } = await seedSubmittedPayment([20]);
    const stored = await getPaymentRow(paymentId);
    assert.ok(stored?.proofFileName);

    const response = await app.inject({
      method: 'GET',
      url: `/api/uploads/payments/${stored.proofFileName}`,
    });

    assert.equal(response.statusCode, 404);
  });

  test('unauthenticated request is rejected', async () => {
    const { paymentId } = await seedSubmittedPayment([20]);

    const response = await app.inject({ method: 'GET', url: `/payments/${paymentId}/proof` });

    assert.equal(response.statusCode, 401);
  });
});
