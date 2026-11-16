import './test-setup.js';

import { afterAll, afterEach, beforeAll, beforeEach, describe, test } from 'bun:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { existsSync, readdirSync, unlinkSync } from 'node:fs';
import { join } from 'node:path';
import { eq } from 'drizzle-orm';
import { buildApp } from '../src/app.js';
import { db } from '../src/drizzle/db.js';
import { payment, paymentAllocation, request } from '../src/drizzle/schema.js';
import { createUser, deleteAllRequests, deleteUsers, UserRole } from './helpers.js';

let app: Awaited<ReturnType<typeof buildApp>>;

let studentToken: string;
let otherStudentToken: string;
let studentId: string;
let facultyId: string;

const createdUserIds: string[] = [];

const UPLOADS_PAYMENTS_DIR = join(process.cwd(), 'uploads', 'payments');

const VALID_PNG = Buffer.from([
  0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
  0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1f, 0x15, 0xc4,
  0x89, 0x00, 0x00, 0x00, 0x0a, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9c, 0x63, 0x00, 0x01, 0x00, 0x00,
  0x05, 0x00, 0x01, 0x0d, 0x0a, 0x2d, 0xb4, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44, 0xae,
  0x42, 0x60, 0x82,
]);

function cleanUploadsDir() {
  if (!existsSync(UPLOADS_PAYMENTS_DIR)) return;
  for (const file of readdirSync(UPLOADS_PAYMENTS_DIR)) {
    if (file === '.gitkeep') continue;
    try {
      unlinkSync(join(UPLOADS_PAYMENTS_DIR, file));
    } catch {
      /* ignore */
    }
  }
}

async function clearPaymentsAndRequests() {
  await db.delete(paymentAllocation);
  await db.delete(payment);
  await deleteAllRequests();
}

async function seedFine(
  userId: string,
  amount: number,
  options: { returned?: boolean; settled?: boolean } = {},
) {
  const now = new Date().toISOString();
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
      returnedAt: options.returned === false ? null : now,
      lateFeeAccrued: amount,
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

async function createIntent(token: string, payload?: unknown) {
  return app.inject({
    method: 'POST',
    url: '/payments',
    headers: { authorization: `Bearer ${token}` },
    payload: payload as Record<string, unknown> | undefined,
  });
}

async function submit(token: string, paymentId: string, parts: Parameters<typeof buildMultipartBody>[0]) {
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

  createdUserIds.push(student.id, otherStudent.id, faculty.id);
});

afterEach(async () => {
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
    assert.deepEqual(
      allocatedRequestIds,
      fines.map((fine) => fine.id).sort(),
    );

    assert.equal(body.upi.reference, body.payment.id);
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
});

describe('POST /payments/:id/submit', () => {
  test('a student can submit UTR + proof for their own PENDING payment', async () => {
    const fine = await seedFine(studentId, 20);
    const intent = await createIntent(studentToken);
    const paymentId = intent.json().payment.id as string;

    const response = await submit(studentToken, paymentId, [
      { name: 'utr', value: '  UTR123456  ' },
      proofPart(),
    ]);

    assert.equal(response.statusCode, 200);
    const submitted = response.json().payment;
    assert.equal(submitted.status, 'SUBMITTED');
    assert.equal(submitted.utr, 'UTR123456');
    assert.ok(submitted.submittedAt);
    assert.ok(submitted.proofImageUrl.includes('/uploads/payments/'));
    assert.ok(submitted.proofImageUrl.endsWith('.webp'));

    const stored = await getPaymentRow(paymentId);
    assert.ok(stored);
    assert.equal(stored.status, 'SUBMITTED');
    assert.equal(stored.utr, 'UTR123456');
    assert.ok(stored.proofImageUrl);

    const proofFilename = stored.proofImageUrl?.split('/').pop();
    assert.ok(proofFilename);
    assert.ok(existsSync(join(UPLOADS_PAYMENTS_DIR, proofFilename)));

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

    const response = await submit(studentToken, paymentId, [{ name: 'utr', value: 'UTR123456' }]);

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
      { name: 'utr', value: 'UTR123456' },
      proofPart(),
    ]);

    assert.equal(response.statusCode, 403);

    const stored = await getPaymentRow(paymentId);
    assert.equal(stored?.status, 'PENDING');
    assert.equal(stored?.utr, null);

    const leftovers = existsSync(UPLOADS_PAYMENTS_DIR) ? readdirSync(UPLOADS_PAYMENTS_DIR) : [];
    assert.equal(leftovers.length, 0);
  });

  test('submitting an already-SUBMITTED payment is rejected (409)', async () => {
    await seedFine(studentId, 20);
    const intent = await createIntent(studentToken);
    const paymentId = intent.json().payment.id as string;

    const first = await submit(studentToken, paymentId, [
      { name: 'utr', value: 'UTR123456' },
      proofPart(),
    ]);
    assert.equal(first.statusCode, 200);

    const second = await submit(studentToken, paymentId, [
      { name: 'utr', value: 'UTR999999' },
      proofPart(),
    ]);

    assert.equal(second.statusCode, 409);
    assert.equal(second.json().code, 'PAYMENT_NOT_PENDING');

    const stored = await getPaymentRow(paymentId);
    assert.equal(stored?.utr, 'UTR123456');
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
        { name: 'utr', value: 'UTR123456' },
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
      { name: 'utr', value: 'UTR123456' },
      proofPart(),
    ]);
    assert.equal(submitted.statusCode, 200);

    const afterSubmit = await getRequestRow(fine.id);
    assert.equal(afterSubmit?.fineSettledAt, null);
    assert.equal(afterSubmit?.lateFeeAccrued, 33);
  });
});
