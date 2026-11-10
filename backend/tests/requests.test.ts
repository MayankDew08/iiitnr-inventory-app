import './test-setup.js';

import { describe, test, beforeAll, afterAll, beforeEach, afterEach } from 'bun:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { eq } from 'drizzle-orm';
import { buildApp } from '../src/app.js';
import { db } from '../src/drizzle/db.js';
import { component, request } from '../src/drizzle/schema.js';
import * as RequestService from '../src/services/RequestService.js';
import { expireOverdueRequests } from '../src/services/request-expiry.js';
import {
  createComponent,
  createRequest,
  createUser,
  deleteAllRequests,
  deleteComponents,
  deleteUsers,
  findComponentById,
  findRequestById,
  UserRole,
} from './helpers.js';

let app: Awaited<ReturnType<typeof buildApp>>;
let adminToken: string;
let studentToken: string;
let facultyToken: string;
let studentId: string;
let adminUserId: string;
let facultyId: string;
let createdComponentIds: string[] = [];

const createdOtherUserIds: string[] = [];
const requestStatus = {
  APPROVED: 'APPROVED',
  ISSUED: 'ISSUED',
  PARTIALLY_ISSUED: 'PARTIALLY_ISSUED',
  RETURNED: 'RETURNED',
  PARTIALLY_RETURNED: 'PARTIALLY_RETURNED',
} as const;

beforeAll(async () => {
  app = await buildApp();

  const suffix = randomUUID();
  const { hash } = await import('bcryptjs');
  const passwordHash = await hash('password123', 12);

  const adminUser = await createUser({
    email: `admin_${suffix}@example.com`,
    passwordHash,
    name: 'Admin User',
    role: UserRole.ADMIN,
  });
  adminUserId = adminUser.id;
  adminToken = app.jwt.sign({ sub: adminUser.id, role: adminUser.role }, { expiresIn: '1h' });

  const studentUser = await createUser({
    email: `student_${suffix}@example.com`,
    passwordHash,
    name: 'Student User',
    role: UserRole.STUDENT,
  });
  studentId = studentUser.id;
  studentToken = app.jwt.sign({ sub: studentUser.id, role: studentUser.role }, { expiresIn: '1h' });

  const facultyUser = await createUser({
    email: `faculty_${suffix}@example.com`,
    passwordHash,
    name: 'Faculty User',
    role: UserRole.FACULTY,
  });
  facultyId = facultyUser.id;
  facultyToken = app.jwt.sign({ sub: facultyUser.id, role: facultyUser.role }, { expiresIn: '1h' });
});

afterAll(async () => {
  await deleteAllRequests();
  const userIds = [adminUserId, studentId, facultyId, ...createdOtherUserIds].filter(Boolean);
  await deleteUsers(userIds);
  await app.close();
});

describe('Request API', () => {
  beforeEach(async () => {
    await deleteAllRequests();
    createdComponentIds = [];
  });

  afterEach(async () => {
    await deleteAllRequests();
    if (createdComponentIds.length > 0) {
      await deleteComponents(createdComponentIds);
    }
  });

  describe('GET /faculty - List faculty', () => {
    test('returns 401 without token', async () => {
      const response = await app.inject({
        method: 'GET',
        url: '/faculty',
      });
      assert.equal(response.statusCode, 401);
    });

    test('returns list of faculty users', async () => {
      const response = await app.inject({
        method: 'GET',
        url: '/faculty',
        headers: { authorization: `Bearer ${studentToken}` },
      });

      assert.equal(response.statusCode, 200);
      const body = response.json();
      assert.ok(Array.isArray(body.faculty));
      assert.ok(
        body.faculty.some(
          (u: { id: string; role: string }) => u.id === facultyId && u.role === 'FACULTY',
        ),
      );
    });
  });

  describe('POST /requests - Create request', () => {
    test('returns 401 without token', async () => {
      const response = await app.inject({
        method: 'POST',
        url: '/requests',
        payload: {
          items: [{ componentId: 'component-1', quantity: 1 }],
        },
      });

      assert.equal(response.statusCode, 401);
    });

    test('returns 400 when items are missing', async () => {
      const response = await app.inject({
        method: 'POST',
        url: '/requests',
        headers: {
          authorization: `Bearer ${studentToken}`,
        },
        payload: {},
      });

      assert.equal(response.statusCode, 400);
    });

    test('returns 400 when targetFacultyId is missing', async () => {
      const component = await createComponent({
        name: 'Resistor',
        totalQuantity: 10,
        availableQuantity: 10,
      });
      createdComponentIds.push(component.id);

      const response = await app.inject({
        method: 'POST',
        url: '/requests',
        headers: { authorization: `Bearer ${studentToken}` },
        payload: {
          items: [{ componentId: component.id, quantity: 1 }],
          projectTitle: 'Test',
        },
      });

      assert.equal(response.statusCode, 400);
    });

    test('returns 400 when projectTitle is missing', async () => {
      const component = await createComponent({
        name: 'Resistor',
        totalQuantity: 10,
        availableQuantity: 10,
      });
      createdComponentIds.push(component.id);

      const response = await app.inject({
        method: 'POST',
        url: '/requests',
        headers: { authorization: `Bearer ${studentToken}` },
        payload: {
          items: [{ componentId: component.id, quantity: 1 }],
          targetFacultyId: facultyId,
        },
      });

      assert.equal(response.statusCode, 400);
    });

    test('returns 400 when quantity is invalid', async () => {
      const component = await createComponent({
        name: 'Resistor',
        totalQuantity: 10,
        availableQuantity: 10,
      });
      createdComponentIds.push(component.id);

      const response = await app.inject({
        method: 'POST',
        url: '/requests',
        headers: { authorization: `Bearer ${studentToken}` },
        payload: {
          items: [{ componentId: component.id, quantity: 0 }],
          targetFacultyId: facultyId,
          projectTitle: 'Test',
        },
      });

      assert.equal(response.statusCode, 400);
    });

    test('returns 400 when item does not exist', async () => {
      const response = await app.inject({
        method: 'POST',
        url: '/requests',
        headers: { authorization: `Bearer ${studentToken}` },
        payload: {
          items: [{ componentId: 'missing-component', quantity: 2 }],
          targetFacultyId: facultyId,
          projectTitle: 'Test',
        },
      });

      assert.equal(response.statusCode, 400);
    });

    test('creates a request with multiple items', async () => {
      const item1 = await createComponent({
        name: 'Arduino',
        totalQuantity: 5,
        availableQuantity: 5,
      });
      const item2 = await createComponent({
        name: 'Breadboard',
        totalQuantity: 20,
        availableQuantity: 20,
      });
      createdComponentIds.push(item1.id, item2.id);

      const response = await app.inject({
        method: 'POST',
        url: '/requests',
        headers: { authorization: `Bearer ${studentToken}` },
        payload: {
          items: [
            { componentId: item1.id, quantity: 1 },
            { componentId: item2.id, quantity: 2 },
          ],
          targetFacultyId: facultyId,
          projectTitle: 'Multi-Item Project',
        },
      });

      assert.equal(response.statusCode, 201);
      const body = response.json();
      assert.equal(body.request.userId, studentId);
      assert.equal(body.request.status, 'PENDING');
      assert.equal(body.request.items.length, 2);
      assert.equal(body.request.targetFacultyId, facultyId);
    });

    test('creates a request with targetFacultyId', async () => {
      const item = await createComponent({
        name: 'Requested to Faculty',
        totalQuantity: 5,
        availableQuantity: 5,
      });
      createdComponentIds.push(item.id);

      const response = await app.inject({
        method: 'POST',
        url: '/requests',
        headers: { authorization: `Bearer ${studentToken}` },
        payload: {
          items: [{ componentId: item.id, quantity: 1 }],
          targetFacultyId: facultyId,
          projectTitle: 'Faculty Request',
        },
      });

      assert.equal(response.statusCode, 201);
      const body = response.json();
      assert.equal(body.request.targetFacultyId, facultyId);
      assert.ok(body.request.targetFaculty);
      assert.equal(body.request.targetFaculty.id, facultyId);
      assert.equal(body.request.targetFaculty.role, 'FACULTY');
    });

    test('returns 400 for invalid targetFacultyId', async () => {
      const item = await createComponent({
        name: 'Invalid Faculty',
        totalQuantity: 5,
        availableQuantity: 5,
      });
      createdComponentIds.push(item.id);

      const response = await app.inject({
        method: 'POST',
        url: '/requests',
        headers: { authorization: `Bearer ${studentToken}` },
        payload: {
          items: [{ componentId: item.id, quantity: 1 }],
          targetFacultyId: studentId,
          projectTitle: 'Test',
        },
      });

      assert.equal(response.statusCode, 400);
    });

    test('creates a request with projectTitle', async () => {
      const item = await createComponent({
        name: 'With Project',
        totalQuantity: 5,
        availableQuantity: 5,
      });
      createdComponentIds.push(item.id);

      const response = await app.inject({
        method: 'POST',
        url: '/requests',
        headers: { authorization: `Bearer ${studentToken}` },
        payload: {
          items: [{ componentId: item.id, quantity: 1 }],
          targetFacultyId: facultyId,
          projectTitle: 'My IoT Project',
        },
      });

      assert.equal(response.statusCode, 201);
      const body = response.json();
      assert.equal(body.request.projectTitle, 'My IoT Project');
    });

    test('returns 400 for duplicate componentId in request', async () => {
      const item = await createComponent({
        name: 'Duplicate Check',
        totalQuantity: 10,
        availableQuantity: 10,
      });
      createdComponentIds.push(item.id);

      const response = await app.inject({
        method: 'POST',
        url: '/requests',
        headers: { authorization: `Bearer ${studentToken}` },
        payload: {
          items: [
            { componentId: item.id, quantity: 1 },
            { componentId: item.id, quantity: 2 },
          ],
          targetFacultyId: facultyId,
          projectTitle: 'Duplicate Project',
        },
      });

      assert.equal(response.statusCode, 400);
      assert.ok(response.json().error.includes('duplicate componentId'));
    });
  });

  describe('DELETE /requests/:id - Retract request', () => {
    test('student can delete own pending request', async () => {
      const component = await createComponent({
        name: 'To Delete',
        totalQuantity: 5,
        availableQuantity: 5,
      });
      createdComponentIds.push(component.id);

      const createResponse = await app.inject({
        method: 'POST',
        url: '/requests',
        headers: { authorization: `Bearer ${studentToken}` },
        payload: {
          items: [{ componentId: component.id, quantity: 1 }],
          targetFacultyId: facultyId,
          projectTitle: 'To Delete',
        },
      });

      assert.equal(createResponse.statusCode, 201);
      const createdId = createResponse.json().request.id;

      const deleteResponse = await app.inject({
        method: 'DELETE',
        url: `/requests/${createdId}`,
        headers: {
          authorization: `Bearer ${studentToken}`,
        },
      });

      assert.equal(deleteResponse.statusCode, 204);
      const exists = await findRequestById(createdId);
      assert.equal(exists, null);
    });

    test('student cannot delete someone else request', async () => {
      const component = await createComponent({
        name: 'Other User Component',
        totalQuantity: 5,
        availableQuantity: 5,
      });
      createdComponentIds.push(component.id);

      const otherUser = await createUser({
        email: `other_${crypto.randomUUID()}@example.com`,
        passwordHash: 'hash',
        name: 'Other User',
        role: UserRole.STUDENT,
      });
      createdOtherUserIds.push(otherUser.id);

      const req = await createRequest({
        userId: otherUser.id,
        targetFacultyId: facultyId,
        projectTitle: 'Other User Project',
        items: [{ componentId: component.id, quantity: 1 }],
      });

      const deleteResponse = await app.inject({
        method: 'DELETE',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${studentToken}` },
      });

      assert.equal(deleteResponse.statusCode, 403);
    });

    test('cannot delete non-pending request', async () => {
      const component = await createComponent({
        name: 'Approved Component',
        totalQuantity: 5,
        availableQuantity: 5,
      });
      createdComponentIds.push(component.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Approved Component',
        status: requestStatus.APPROVED,
        items: [{ componentId: component.id, quantity: 1 }],
      });

      const deleteResponse = await app.inject({
        method: 'DELETE',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${studentToken}` },
      });

      assert.equal(deleteResponse.statusCode, 400);
      const stillExists = await findRequestById(req.id);
      assert.ok(stillExists);
    });
  });

  describe('GET /requests - List by user/status', () => {
    test('returns 401 without token', async () => {
      const response = await app.inject({
        method: 'GET',
        url: '/requests',
      });

      assert.equal(response.statusCode, 401);
    });

    test('student only sees own requests even with userId filter', async () => {
      const item = await createComponent({
        name: 'Sensor',
        totalQuantity: 10,
        availableQuantity: 10,
      });
      createdComponentIds.push(item.id);

      const otherUser = await createUser({
        email: `other_${crypto.randomUUID()}@example.com`,
        passwordHash: 'hash',
        name: 'Other User',
        role: UserRole.STUDENT,
      });
      createdOtherUserIds.push(otherUser.id);

      await createRequest({
        userId: otherUser.id,
        targetFacultyId: facultyId,
        projectTitle: 'Other User Request',
        items: [{ componentId: item.id, quantity: 1 }],
      });

      await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Student Request',
        items: [{ componentId: item.id, quantity: 1 }],
      });

      const response = await app.inject({
        method: 'GET',
        url: `/requests?userId=${otherUser.id}`,
        headers: {
          authorization: `Bearer ${studentToken}`,
        },
      });

      assert.equal(response.statusCode, 200);
      const body = response.json();
      assert.equal(body.requests.length, 1);
      assert.equal(body.requests[0].userId, studentId);
    });

    test('admin can filter by user and status', async () => {
      const item = await createComponent({
        name: 'Display',
        totalQuantity: 10,
        availableQuantity: 10,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Display Project',
        status: requestStatus.APPROVED,
        items: [{ componentId: item.id, quantity: 1 }],
      });

      const response = await app.inject({
        method: 'GET',
        url: `/requests?userId=${studentId}&status=APPROVED`,
        headers: { authorization: `Bearer ${adminToken}` },
      });

      assert.equal(response.statusCode, 200);
      const body = response.json();
      assert.ok(body.requests.some((r: { id: string }) => r.id === req.id));
    });

    test('returns 400 for invalid status filter', async () => {
      const response = await app.inject({
        method: 'GET',
        url: '/requests?status=INVALID_STATUS',
        headers: { authorization: `Bearer ${adminToken}` },
      });

      assert.equal(response.statusCode, 400);
      assert.ok(response.json().error.includes('invalid status'));
    });

    test('overdue checked-out requests are returned as EXPIRED', async () => {
      const item = await createComponent({
        name: 'Overdue Sensor',
        totalQuantity: 10,
        availableQuantity: 9,
      });
      createdComponentIds.push(item.id);
      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Overdue Checkout',
        status: 'ISSUED',
        items: [{ componentId: item.id, quantity: 1 }],
      });
      const overdueAt = new Date(Date.now() - 60 * 1000).toISOString();
      await db.update(request).set({ returnDueAt: overdueAt }).where(eq(request.id, req.id));

      const response = await app.inject({
        method: 'GET',
        url: '/requests?status=EXPIRED',
        headers: { authorization: `Bearer ${adminToken}` },
      });

      assert.equal(response.statusCode, 200);
      const body = response.json();
      assert.ok(
        body.requests.some(
          (r: { id: string; status: string }) => r.id === req.id && r.status === 'EXPIRED',
        ),
      );
      assert.equal((await findRequestById(req.id))?.status, 'EXPIRED');
    });

    for (const partialStatus of ['PARTIALLY_ISSUED', 'PARTIALLY_RETURNED'] as const) {
      test(`overdue ${partialStatus} requests are swept to EXPIRED`, async () => {
        const item = await createComponent({
          name: `Overdue ${partialStatus} Sensor`,
          totalQuantity: 10,
          availableQuantity: 9,
        });
        createdComponentIds.push(item.id);
        const req = await createRequest({
          userId: studentId,
          targetFacultyId: facultyId,
          projectTitle: `Overdue ${partialStatus} Checkout`,
          status: partialStatus,
          items: [{ componentId: item.id, quantity: 2, fulfilledQuantity: 1 }],
        });
        const overdueAt = new Date(Date.now() - 60 * 1000).toISOString();
        await db.update(request).set({ returnDueAt: overdueAt }).where(eq(request.id, req.id));

        const response = await app.inject({
          method: 'GET',
          url: '/requests?status=EXPIRED',
          headers: { authorization: `Bearer ${adminToken}` },
        });

        assert.equal(response.statusCode, 200);
        const body = response.json();
        assert.ok(
          body.requests.some(
            (r: { id: string; status: string }) => r.id === req.id && r.status === 'EXPIRED',
          ),
        );
        assert.equal((await findRequestById(req.id))?.status, 'EXPIRED');
      });
    }

    test('overdue REQUESTED_RENEW requests are not swept to EXPIRED', async () => {
      const item = await createComponent({
        name: 'Overdue Renewal Sensor',
        totalQuantity: 10,
        availableQuantity: 9,
      });
      createdComponentIds.push(item.id);
      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Overdue Renewal Pending',
        status: 'REQUESTED_RENEW',
        items: [{ componentId: item.id, quantity: 1, fulfilledQuantity: 1 }],
      });
      const overdueAt = new Date(Date.now() - 60 * 1000).toISOString();
      await db.update(request).set({ returnDueAt: overdueAt }).where(eq(request.id, req.id));

      const response = await app.inject({
        method: 'GET',
        url: '/requests?status=EXPIRED',
        headers: { authorization: `Bearer ${adminToken}` },
      });

      assert.equal(response.statusCode, 200);
      const body = response.json();
      assert.ok(
        !body.requests.some((r: { id: string }) => r.id === req.id),
        'a pending renewal must not expire while faculty have not decided',
      );
      assert.equal((await findRequestById(req.id))?.status, 'REQUESTED_RENEW');
    });

    test('expiring a request writes the outstanding stock off the component total', async () => {
      const item = await createComponent({
        name: 'Write Off Sensor',
        totalQuantity: 10,
        availableQuantity: 6,
      });
      createdComponentIds.push(item.id);
      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Write Off Checkout',
        status: 'ISSUED',
        items: [{ componentId: item.id, quantity: 4, fulfilledQuantity: 4 }],
      });
      const overdueAt = new Date(Date.now() - 60 * 1000).toISOString();
      await db.update(request).set({ returnDueAt: overdueAt }).where(eq(request.id, req.id));

      const response = await app.inject({
        method: 'GET',
        url: '/requests?status=EXPIRED',
        headers: { authorization: `Bearer ${adminToken}` },
      });

      assert.equal(response.statusCode, 200);
      assert.equal((await findRequestById(req.id))?.status, 'EXPIRED');

      const updatedItem = (await findRequestById(req.id))?.items[0];
      assert.equal(updatedItem?.writtenOffQuantity, 4);
      assert.equal(updatedItem?.returnedQuantity, 0);

      const updatedComponent = await findComponentById(item.id);
      // Written off: leaves totalQuantity, but available was already reduced at issue.
      assert.equal(updatedComponent?.totalQuantity, 6);
      assert.equal(updatedComponent?.availableQuantity, 6);
    });

    test('repeated expiry sweeps do not write off twice', async () => {
      const item = await createComponent({
        name: 'Idempotent Write Off Sensor',
        totalQuantity: 10,
        availableQuantity: 7,
      });
      createdComponentIds.push(item.id);
      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Idempotent Write Off',
        status: 'ISSUED',
        items: [{ componentId: item.id, quantity: 3, fulfilledQuantity: 3 }],
      });
      const overdueAt = new Date(Date.now() - 60 * 1000).toISOString();
      await db.update(request).set({ returnDueAt: overdueAt }).where(eq(request.id, req.id));

      const firstSweep = await expireOverdueRequests();
      assert.ok(firstSweep.some((r) => r.id === req.id));
      const afterFirst = await findComponentById(item.id);
      assert.equal(afterFirst?.totalQuantity, 7);

      const secondSweep = await expireOverdueRequests();
      assert.ok(!secondSweep.some((r) => r.id === req.id));
      const afterSecond = await findComponentById(item.id);
      assert.equal(afterSecond?.totalQuantity, 7);
      assert.equal((await findRequestById(req.id))?.items[0].writtenOffQuantity, 3);
    });

    test('faculty sees all requests targeting them', async () => {
      const item = await createComponent({
        name: 'Sensor',
        totalQuantity: 10,
        availableQuantity: 10,
      });
      createdComponentIds.push(item.id);

      const approvedRequest = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Approved Request',
        status: 'APPROVED',
        items: [{ componentId: item.id, quantity: 1 }],
      });

      const pendingRequest = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Pending Request',
        status: 'PENDING',
        items: [{ componentId: item.id, quantity: 1 }],
      });

      const rejectedRequest = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Rejected Request',
        status: 'REJECTED',
        items: [{ componentId: item.id, quantity: 1 }],
      });

      const otherFaculty = await createUser({
        email: `other_faculty_${crypto.randomUUID()}@example.com`,
        passwordHash: 'hash',
        name: 'Other Faculty',
        role: UserRole.FACULTY,
      });
      createdOtherUserIds.push(otherFaculty.id);

      const otherFacultyRequest = await createRequest({
        userId: studentId,
        targetFacultyId: otherFaculty.id,
        projectTitle: 'Other Faculty Request',
        status: 'PENDING',
        items: [{ componentId: item.id, quantity: 1 }],
      });

      const response = await app.inject({
        method: 'GET',
        url: '/requests',
        headers: {
          authorization: `Bearer ${facultyToken}`,
        },
      });

      assert.equal(response.statusCode, 200);
      const body = response.json();
      // Should see all 3 requests targeting this faculty
      assert.equal(body.requests.length, 3);

      const requestIds = body.requests.map((r: { id: string }) => r.id);
      assert.ok(
        body.requests.every((r: { targetFacultyId: string }) => r.targetFacultyId === facultyId),
      );
      assert.ok(requestIds.includes(pendingRequest.id));
      assert.ok(requestIds.includes(approvedRequest.id));
      assert.ok(requestIds.includes(rejectedRequest.id));

      // Verify requests not targeting this faculty are not included
      assert.ok(!requestIds.includes(otherFacultyRequest.id));
    });
  });

  describe('PUT /requests/:id - Update request status (approve/reject)', () => {
    test('returns 401 without token', async () => {
      const response = await app.inject({
        method: 'PUT',
        url: '/requests/some-id',
        payload: { status: 'APPROVED' },
      });

      assert.equal(response.statusCode, 401);
    });

    test('returns 400 when status is missing', async () => {
      const response = await app.inject({
        method: 'PUT',
        url: '/requests/some-id',
        headers: { authorization: `Bearer ${facultyToken}` },
        payload: {},
      });

      assert.equal(response.statusCode, 400);
    });

    test('returns 400 when status is invalid', async () => {
      const response = await app.inject({
        method: 'PUT',
        url: '/requests/some-id',
        headers: { authorization: `Bearer ${facultyToken}` },
        payload: { status: 'INVALID' },
      });

      assert.equal(response.statusCode, 400);
    });

    test('faculty can approve pending request targeting them', async () => {
      const item = await createComponent({
        name: 'Sensor',
        totalQuantity: 10,
        availableQuantity: 10,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Approve Test',
        status: 'PENDING',
        items: [{ componentId: item.id, quantity: 1 }],
      });

      const response = await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${facultyToken}` },
        payload: { status: 'APPROVED' },
      });

      assert.equal(response.statusCode, 200);
      const body = response.json();
      assert.equal(body.request.status, 'APPROVED');
      assert.equal(body.request.id, req.id);
    });

    test('faculty can reject pending request targeting them', async () => {
      const item = await createComponent({
        name: 'Sensor',
        totalQuantity: 10,
        availableQuantity: 10,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Reject Test',
        status: 'PENDING',
        items: [{ componentId: item.id, quantity: 1 }],
      });

      const response = await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${facultyToken}` },
        payload: { status: 'REJECTED' },
      });

      assert.equal(response.statusCode, 200);
      const body = response.json();
      assert.equal(body.request.status, 'REJECTED');
      assert.equal(body.request.id, req.id);
    });

    describe('GET /requests/export/csv', () => {
      test('returns 401 without token', async () => {
        const response = await app.inject({
          method: 'GET',
          url: '/requests/export/csv',
        });
        assert.equal(response.statusCode, 401);
      });

      test('returns 403 for STUDENT role', async () => {
        const response = await app.inject({
          method: 'GET',
          url: '/requests/export/csv',
          headers: { authorization: `Bearer ${studentToken}` },
        });
        assert.equal(response.statusCode, 403);
      });

      test('returns 200 and CSV content for ADMIN', async () => {
        const item = await createComponent({
          name: 'Export Request Item',
          totalQuantity: 10,
          availableQuantity: 10,
        });
        createdComponentIds.push(item.id);

        await createRequest({
          userId: studentId,
          targetFacultyId: facultyId,
          projectTitle: 'CSV Export Project',
          status: 'PENDING',
          items: [{ componentId: item.id, quantity: 2 }],
        });

        try {
          const response = await app.inject({
            method: 'GET',
            url: '/requests/export/csv',
            headers: { authorization: `Bearer ${adminToken}` },
          });

          assert.equal(response.statusCode, 200);
          assert.equal(response.headers['content-type'], 'text/csv');
          assert.ok(response.payload.includes('CSV Export Project'));
          assert.ok(response.payload.includes('Export Request Item (Req:2)'));
          assert.ok(
            response.payload.includes(
              'Request ID,User Email,User Name,Target Faculty,Project Title,Status,Created At,Return Due At,Late Fee (INR),Items Summary',
            ),
          );
        } finally {
          // cleanup handled by beforeEach / afterEach deleteAllRequests
        }
      });
    });

    test('faculty cannot approve/reject request not targeting them', async () => {
      const item = await createComponent({
        name: 'Sensor',
        totalQuantity: 10,
        availableQuantity: 10,
      });
      createdComponentIds.push(item.id);

      const otherFaculty = await createUser({
        email: `other_faculty_${crypto.randomUUID()}@example.com`,
        passwordHash: 'hash',
        name: 'Other Faculty',
        role: UserRole.FACULTY,
      });
      createdOtherUserIds.push(otherFaculty.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: otherFaculty.id,
        projectTitle: 'Other Faculty Approve Test',
        status: 'PENDING',
        items: [{ componentId: item.id, quantity: 1 }],
      });

      const response = await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${facultyToken}` },
        payload: { status: 'APPROVED' },
      });

      assert.equal(response.statusCode, 403);
    });

    test('cannot update non-pending request', async () => {
      const item = await createComponent({
        name: 'Sensor',
        totalQuantity: 10,
        availableQuantity: 10,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Cannot Update Non-Pending',
        status: 'APPROVED',
        items: [{ componentId: item.id, quantity: 1 }],
      });

      const response = await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${facultyToken}` },
        payload: { status: 'REJECTED' },
      });

      assert.equal(response.statusCode, 400);
    });

    test('admin can approve/reject any pending request', async () => {
      const item = await createComponent({
        name: 'Sensor',
        totalQuantity: 10,
        availableQuantity: 10,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Admin Approve Test',
        status: 'PENDING',
        items: [{ componentId: item.id, quantity: 1 }],
      });

      const response = await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: { status: 'APPROVED' },
      });

      assert.equal(response.statusCode, 200);
      const body = response.json();
      assert.equal(body.request.status, 'APPROVED');
    });

    test('student cannot approve/reject requests', async () => {
      const item = await createComponent({
        name: 'Sensor',
        totalQuantity: 10,
        availableQuantity: 10,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Student Approve Test',
        status: 'PENDING',
        items: [{ componentId: item.id, quantity: 1 }],
      });

      const response = await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${studentToken}` },
        payload: { status: 'APPROVED' },
      });

      assert.equal(response.statusCode, 403);
    });

    test('ISSUED cannot be set directly from PENDING', async () => {
      const item = await createComponent({
        name: 'Sensor',
        totalQuantity: 10,
        availableQuantity: 10,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Issued Test',
        status: 'PENDING',
        items: [{ componentId: item.id, quantity: 1 }],
      });

      const response = await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: { status: 'ISSUED' },
      });

      assert.equal(response.statusCode, 400);
      assert.ok(response.json().error.includes('PENDING requests can only be'));
    });

    test('ISSUED requires Admin or LA role', async () => {
      const item = await createComponent({
        name: 'Sensor',
        totalQuantity: 10,
        availableQuantity: 10,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Issued Test',
        status: 'APPROVED',
        items: [{ componentId: item.id, quantity: 1 }],
      });

      const response = await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${facultyToken}` },
        payload: { status: 'ISSUED' },
      });

      assert.equal(response.statusCode, 403);
    });

    test('successful ISSUED decrements availableQuantity', async () => {
      const item = await createComponent({
        name: 'Sensor',
        totalQuantity: 10,
        availableQuantity: 10,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Issued Incremental Test',
        status: 'APPROVED',
        items: [{ componentId: item.id, quantity: 3 }],
      });

      const response = await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: { status: 'ISSUED' },
      });

      assert.equal(response.statusCode, 200);
      const updatedComponent = await findComponentById(item.id);
      assert.equal(updatedComponent?.availableQuantity, 7);
    });

    test('returns 400 for insufficient quantity during ISSUED', async () => {
      const item = await createComponent({
        name: 'Sensor',
        totalQuantity: 10,
        availableQuantity: 2,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Insufficient Test',
        status: 'APPROVED',
        items: [{ componentId: item.id, quantity: 5 }],
      });

      const response = await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: { status: 'ISSUED' },
      });

      assert.equal(response.statusCode, 400);
      assert.ok(response.json().error.includes('insufficient quantity'));
    });

    test('successful RETURNED increments availableQuantity', async () => {
      const item = await createComponent({
        name: 'Sensor',
        totalQuantity: 10,
        availableQuantity: 10,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Return Test',
        status: 'APPROVED',
        items: [{ componentId: item.id, quantity: 4 }],
      });

      const fulfillRes = await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: { status: 'ISSUED' },
      });
      assert.equal(fulfillRes.statusCode, 200);
      assert.equal((await findComponentById(item.id))?.availableQuantity, 6);

      const returnRes = await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: { status: 'RETURNED' },
      });

      assert.equal(returnRes.statusCode, 200);
      const body = returnRes.json();
      assert.equal(body.request.status, 'RETURNED');
      assert.ok(body.request.returnedAt);
      const updatedReq = await findRequestById(req.id);
      assert.equal(updatedReq?.items[0].fulfilledQuantity, 4);
      assert.equal(updatedReq?.items[0].returnedQuantity, 4);
      const updatedComponent = await findComponentById(item.id);
      assert.equal(updatedComponent?.availableQuantity, 10);
    });

    test('RETURNED requires Admin or LA role', async () => {
      const item = await createComponent({
        name: 'Sensor',
        totalQuantity: 10,
        availableQuantity: 10,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Return Auth Test',
        status: 'APPROVED',
        items: [{ componentId: item.id, quantity: 1 }],
      });

      await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: { status: 'ISSUED' },
      });

      const response = await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${facultyToken}` },
        payload: { status: 'RETURNED' },
      });

      assert.equal(response.statusCode, 403);
    });

    test('APPROVED request cannot be set to RETURNED directly', async () => {
      const item = await createComponent({
        name: 'Sensor',
        totalQuantity: 10,
        availableQuantity: 10,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Skip Fulfill Return',
        status: 'APPROVED',
        items: [{ componentId: item.id, quantity: 1 }],
      });

      const response = await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: { status: 'RETURNED' },
      });

      assert.equal(response.statusCode, 400);
      assert.ok(response.json().error.includes('ISSUED'));
    });

    test('returnDueAt is set to 1 month from fulfilled time', async () => {
      const item = await createComponent({
        name: 'Sensor',
        totalQuantity: 10,
        availableQuantity: 10,
      });
      createdComponentIds.push(item.id);
      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Return Due At Test',
        status: 'APPROVED',
        items: [{ componentId: item.id, quantity: 1 }],
      });

      const res = await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: { status: 'ISSUED' },
      });
      const { returnDueAt } = res.json().request;
      const actualTime = new Date(returnDueAt).getTime();
      const expectedTime =
        new Date(res.json().request.fulfilledAt).getTime() + 30 * 24 * 60 * 60 * 1000;

      const diff = Math.abs(actualTime - expectedTime);
      assert.ok(
        diff < 10000,
        `Time difference too large: ${diff}ms, expected ${expectedTime}ms, actual ${actualTime}ms`,
      );
    });

    test('when return would exceed totalQuantity, total is raised to match new available', async () => {
      const item = await createComponent({
        name: 'Sensor',
        totalQuantity: 10,
        availableQuantity: 10,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Raise Total Return',
        status: 'APPROVED',
        items: [{ componentId: item.id, quantity: 3 }],
      });

      await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: { status: 'ISSUED' },
      });

      const now = new Date().toISOString();
      await db
        .update(component)
        .set({ totalQuantity: 2, updatedAt: now })
        .where(eq(component.id, item.id));

      const response = await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: { status: 'RETURNED' },
      });

      assert.equal(response.statusCode, 200);
      const updated = await findComponentById(item.id);
      assert.equal(updated?.availableQuantity, 10);
      assert.equal(updated?.totalQuantity, 10);
    });

    test('student can request renewal', async () => {
      const item = await createComponent({
        name: 'Sensor',
        totalQuantity: 10,
        availableQuantity: 10,
      });
      createdComponentIds.push(item.id);
      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Renewal Request Test',
        status: 'ISSUED',
        items: [{ componentId: item.id, quantity: 1 }],
      });
      const response = await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${studentToken}` },
        payload: { status: 'REQUESTED_RENEW', lastRenewReason: 'Renewal Reason' },
      });
      assert.equal(response.statusCode, 200);
      const updated = await findRequestById(req.id);
      assert.ok(updated?.lastRenewReason);
      assert.equal(updated?.lastRenewReason, 'Renewal Reason');
    });

    test('faculty can approve renewal request', async () => {
      const item = await createComponent({
        name: 'Sensor',
        totalQuantity: 10,
        availableQuantity: 10,
      });
      createdComponentIds.push(item.id);
      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Renewed Test',
        status: 'REQUESTED_RENEW',
        items: [{ componentId: item.id, quantity: 1 }],
      });
      const response = await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${facultyToken}` },
        payload: { status: 'RENEWED' },
      });
      assert.equal(response.statusCode, 200);
      const updated = await findRequestById(req.id);
      assert.equal(updated?.status, 'RENEWED');
    });

    test('successful RETURNED from RENEWED increments availableQuantity', async () => {
      const item = await createComponent({
        name: 'Sensor',
        totalQuantity: 10,
        availableQuantity: 6,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Renewed Return Test',
        status: 'RENEWED',
        items: [{ componentId: item.id, quantity: 4, fulfilledQuantity: 4 }],
      });

      const response = await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: { status: 'RETURNED' },
      });

      assert.equal(response.statusCode, 200);
      const body = response.json();
      assert.equal(body.request.status, 'RETURNED');
      const updatedReq = await findRequestById(req.id);
      assert.equal(updatedReq?.items[0].fulfilledQuantity, 4);
      assert.equal(updatedReq?.items[0].returnedQuantity, 4);
      const updatedComponent = await findComponentById(item.id);
      assert.equal(updatedComponent?.availableQuantity, 10);
    });

    test('expired request can be marked returned by admin', async () => {
      const item = await createComponent({
        name: 'Expired Return Sensor',
        totalQuantity: 10,
        availableQuantity: 6,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Expired Return Test',
        status: 'ISSUED',
        items: [{ componentId: item.id, quantity: 4, fulfilledQuantity: 4 }],
      });
      const overdueAt = new Date(Date.now() - 60 * 1000).toISOString();
      await db.update(request).set({ returnDueAt: overdueAt }).where(eq(request.id, req.id));

      const response = await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: { status: 'RETURNED' },
      });

      assert.equal(response.statusCode, 200);
      const body = response.json();
      // The deadline was missed, so the request stays EXPIRED after the late return.
      assert.equal(body.request.status, 'EXPIRED');
      const updatedReq = await findRequestById(req.id);
      assert.equal(updatedReq?.status, 'EXPIRED');
      assert.equal(updatedReq?.items[0].fulfilledQuantity, 4);
      assert.equal(updatedReq?.items[0].returnedQuantity, 4);
      // Expiry wrote the 4 units off the total, the late return reclaimed them.
      assert.equal(updatedReq?.items[0].writtenOffQuantity, 0);
      const updatedComponent = await findComponentById(item.id);
      assert.equal(updatedComponent?.availableQuantity, 10);
      assert.equal(updatedComponent?.totalQuantity, 10);
    });

    test('RENEWED request cannot be set to ISSUED', async () => {
      const item = await createComponent({
        name: 'Sensor',
        totalQuantity: 10,
        availableQuantity: 6,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Renewed Fulfill Test',
        status: 'RENEWED',
        items: [{ componentId: item.id, quantity: 4 }],
      });

      const response = await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: { status: 'ISSUED' },
      });

      assert.equal(response.statusCode, 400);
      assert.ok(response.json().error.includes('RETURNED'));
      assert.equal((await findRequestById(req.id))?.status, 'RENEWED');
      assert.equal((await findComponentById(item.id))?.availableQuantity, 6);
    });
  });

  describe('Partial Issue/Return', () => {
    test('partial issue when stock insufficient for some items sets PARTIALLY_ISSUED', async () => {
      const item1 = await createComponent({
        name: 'Component A',
        totalQuantity: 5,
        availableQuantity: 2,
      });
      const item2 = await createComponent({
        name: 'Component B',
        totalQuantity: 10,
        availableQuantity: 10,
      });
      createdComponentIds.push(item1.id, item2.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Partial Issue Test',
        status: 'APPROVED',
        items: [
          { componentId: item1.id, quantity: 5 },
          { componentId: item2.id, quantity: 3 },
        ],
      });

      const response = await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: {
          status: 'ISSUED',
          issueItems: [
            { componentId: item1.id, quantity: 2 },
            { componentId: item2.id, quantity: 3 },
          ],
        },
      });

      assert.equal(response.statusCode, 200);
      const body = response.json();
      assert.equal(body.request.status, 'PARTIALLY_ISSUED');
      const updatedReq = await findRequestById(req.id);
      assert.ok(updatedReq);
      assert.equal(updatedReq.status, 'PARTIALLY_ISSUED');

      const item1Row = updatedReq.items.find(
        (i: { componentId: string; fulfilledQuantity: number }) => i.componentId === item1.id,
      );
      const item2Row = updatedReq.items.find(
        (i: { componentId: string; fulfilledQuantity: number }) => i.componentId === item2.id,
      );
      assert.equal(item1Row?.fulfilledQuantity, 2);
      assert.equal(item2Row?.fulfilledQuantity, 3);

      const comp1 = await findComponentById(item1.id);
      const comp2 = await findComponentById(item2.id);
      assert.equal(comp1?.availableQuantity, 0);
      assert.equal(comp2?.availableQuantity, 7);
    });

    test('partial issue with issueItems matching requested qty sets ISSUED', async () => {
      const item = await createComponent({
        name: 'Full Issue',
        totalQuantity: 10,
        availableQuantity: 10,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Full Issue Test',
        status: 'APPROVED',
        items: [{ componentId: item.id, quantity: 3 }],
      });

      const response = await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: {
          status: 'ISSUED',
          issueItems: [{ componentId: item.id, quantity: 3 }],
        },
      });

      assert.equal(response.statusCode, 200);
      const body = response.json();
      assert.equal(body.request.status, 'ISSUED');
    });

    test('re-issue on PARTIALLY_ISSUED completes remaining quantity', async () => {
      const item = await createComponent({
        name: 'Reissue Component',
        totalQuantity: 10,
        availableQuantity: 10,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Reissue Test',
        status: 'APPROVED',
        items: [{ componentId: item.id, quantity: 5 }],
      });

      await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: {
          status: 'ISSUED',
          issueItems: [{ componentId: item.id, quantity: 2 }],
        },
      });

      let updated = await findRequestById(req.id);
      assert.equal(updated?.status, 'PARTIALLY_ISSUED');
      assert.equal(updated?.items[0].fulfilledQuantity, 2);

      const itemAfterFirst = await findComponentById(item.id);
      assert.equal(itemAfterFirst?.availableQuantity, 8);

      const response = await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: {
          status: 'ISSUED',
          issueItems: [{ componentId: item.id, quantity: 3 }],
        },
      });

      assert.equal(response.statusCode, 200);
      updated = await findRequestById(req.id);
      assert.equal(updated?.status, 'ISSUED');
      assert.equal(updated?.items[0].fulfilledQuantity, 5);

      const itemAfterSecond = await findComponentById(item.id);
      assert.equal(itemAfterSecond?.availableQuantity, 5);
    });

    test('partial return sets PARTIALLY_RETURNED when some items remain', async () => {
      const item1 = await createComponent({
        name: 'Return Comp A',
        totalQuantity: 10,
        availableQuantity: 8,
      });
      const item2 = await createComponent({
        name: 'Return Comp B',
        totalQuantity: 10,
        availableQuantity: 7,
      });
      createdComponentIds.push(item1.id, item2.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Partial Return Test',
        status: 'ISSUED',
        items: [
          { componentId: item1.id, quantity: 3, fulfilledQuantity: 3 },
          { componentId: item2.id, quantity: 2, fulfilledQuantity: 2 },
        ],
      });

      const response = await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: {
          status: 'RETURNED',
          returnItems: [{ componentId: item1.id, quantity: 2 }],
        },
      });

      assert.equal(response.statusCode, 200);
      const body = response.json();
      assert.equal(body.request.status, 'PARTIALLY_RETURNED');

      const updatedReq = await findRequestById(req.id);
      assert.ok(updatedReq);
      assert.equal(updatedReq.status, 'PARTIALLY_RETURNED');

      const item1Row = updatedReq.items.find(
        (i: { componentId: string; fulfilledQuantity: number; returnedQuantity: number }) =>
          i.componentId === item1.id,
      );
      const item2Row = updatedReq.items.find(
        (i: { componentId: string; fulfilledQuantity: number; returnedQuantity: number }) =>
          i.componentId === item2.id,
      );
      assert.equal(item1Row?.fulfilledQuantity, 3);
      assert.equal(item1Row?.returnedQuantity, 2);
      assert.equal(item2Row?.fulfilledQuantity, 2);
      assert.equal(item2Row?.returnedQuantity, 0);

      const comp1 = await findComponentById(item1.id);
      const comp2 = await findComponentById(item2.id);
      assert.equal(comp1?.availableQuantity, 10);
      assert.equal(comp2?.availableQuantity, 7);
    });

    test('partial return of all fulfilled quantity sets RETURNED', async () => {
      const item = await createComponent({
        name: 'Full Return',
        totalQuantity: 10,
        availableQuantity: 7,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Full Return Test',
        status: 'ISSUED',
        items: [{ componentId: item.id, quantity: 3, fulfilledQuantity: 3 }],
      });

      const response = await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: {
          status: 'RETURNED',
          returnItems: [{ componentId: item.id, quantity: 3 }],
        },
      });

      assert.equal(response.statusCode, 200);
      const body = response.json();
      assert.equal(body.request.status, 'RETURNED');

      const updatedReq = await findRequestById(req.id);
      assert.ok(updatedReq);
      assert.equal(updatedReq.status, 'RETURNED');
      assert.equal(updatedReq.items[0].fulfilledQuantity, 3);
      assert.equal(updatedReq.items[0].returnedQuantity, 3);

      const comp = await findComponentById(item.id);
      assert.equal(comp?.availableQuantity, 10);
    });

    test('returns 400 when issueItems exceeds available stock', async () => {
      const item = await createComponent({
        name: 'Low Stock',
        totalQuantity: 5,
        availableQuantity: 2,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Exceed Stock Test',
        status: 'APPROVED',
        items: [{ componentId: item.id, quantity: 3 }],
      });

      const response = await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: {
          status: 'ISSUED',
          issueItems: [{ componentId: item.id, quantity: 3 }],
        },
      });

      assert.equal(response.statusCode, 400);
      assert.ok(response.json().error.includes('insufficient quantity'));
    });

    test('returns 400 when returnItems exceeds fulfilled quantity', async () => {
      const item = await createComponent({
        name: 'Return Exceed',
        totalQuantity: 10,
        availableQuantity: 8,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Exceed Return Test',
        status: 'ISSUED',
        items: [{ componentId: item.id, quantity: 2, fulfilledQuantity: 2 }],
      });

      const response = await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: {
          status: 'RETURNED',
          returnItems: [{ componentId: item.id, quantity: 5 }],
        },
      });

      assert.equal(response.statusCode, 400);
      assert.ok(response.json().error);
    });

    test('PARTIALLY_ISSUED can transition to RETURNED', async () => {
      const item = await createComponent({
        name: 'Partial Issued Return',
        totalQuantity: 10,
        availableQuantity: 8,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Partial Issued Return',
        status: 'PARTIALLY_ISSUED',
        items: [{ componentId: item.id, quantity: 5, fulfilledQuantity: 3 }],
      });

      const response = await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: {
          status: 'RETURNED',
          returnItems: [{ componentId: item.id, quantity: 3 }],
        },
      });

      assert.equal(response.statusCode, 200);
      const body = response.json();
      assert.equal(body.request.status, 'RETURNED');
      const updatedReq = await findRequestById(req.id);
      assert.equal(updatedReq?.items[0].fulfilledQuantity, 3);
      assert.equal(updatedReq?.items[0].returnedQuantity, 3);
    });

    test('PARTIALLY_RETURNED can transition to RETURNED', async () => {
      const item = await createComponent({
        name: 'Partial Returned Return',
        totalQuantity: 10,
        availableQuantity: 8,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Partial Returned Return',
        status: 'PARTIALLY_RETURNED',
        items: [{ componentId: item.id, quantity: 5, fulfilledQuantity: 2, returnedQuantity: 1 }],
      });

      const response = await app.inject({
        method: 'PUT',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: {
          status: 'RETURNED',
          returnItems: [{ componentId: item.id, quantity: 1 }],
        },
      });

      assert.equal(response.statusCode, 200);
      const body = response.json();
      assert.equal(body.request.status, 'RETURNED');
      const updatedReq = await findRequestById(req.id);
      assert.equal(updatedReq?.items[0].fulfilledQuantity, 2);
      assert.equal(updatedReq?.items[0].returnedQuantity, 2);
    });
  });

  describe('Concurrent Issue and Return', () => {
    test('two parallel PUT /requests/:id full ISSUED decrements stock exactly once', async () => {
      const item = await createComponent({
        name: 'Concurrent Full Issue Comp',
        totalQuantity: 20,
        availableQuantity: 20,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Concurrent Full Issue Test',
        status: 'APPROVED',
        items: [{ componentId: item.id, quantity: 10 }],
      });

      const responses = await Promise.all([
        app.inject({
          method: 'PUT',
          url: `/requests/${req.id}`,
          headers: { authorization: `Bearer ${adminToken}` },
          payload: { status: 'ISSUED' },
        }),
        app.inject({
          method: 'PUT',
          url: `/requests/${req.id}`,
          headers: { authorization: `Bearer ${adminToken}` },
          payload: { status: 'ISSUED' },
        }),
      ]);

      for (const res of responses) {
        assert.ok(res.statusCode === 200 || res.statusCode === 400);
      }
      assert.ok(responses.some((res) => res.statusCode === 200));

      const updatedReq = await findRequestById(req.id);
      assert.ok(updatedReq);
      assert.equal(updatedReq.status, 'ISSUED');
      assert.equal(updatedReq.items[0].fulfilledQuantity, 10);

      const comp = await findComponentById(item.id);
      assert.equal(comp?.availableQuantity, 10);
    });

    test('two parallel PUT /requests/:id partial ISSUED accumulates fulfilledQuantity and decrements stock properly', async () => {
      const item = await createComponent({
        name: 'Concurrent Partial Issue Comp',
        totalQuantity: 20,
        availableQuantity: 20,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Concurrent Partial Issue Test',
        status: 'APPROVED',
        items: [{ componentId: item.id, quantity: 10 }],
      });

      const [res1, res2] = await Promise.all([
        app.inject({
          method: 'PUT',
          url: `/requests/${req.id}`,
          headers: { authorization: `Bearer ${adminToken}` },
          payload: {
            status: 'ISSUED',
            issueItems: [{ componentId: item.id, quantity: 5 }],
          },
        }),
        app.inject({
          method: 'PUT',
          url: `/requests/${req.id}`,
          headers: { authorization: `Bearer ${adminToken}` },
          payload: {
            status: 'ISSUED',
            issueItems: [{ componentId: item.id, quantity: 5 }],
          },
        }),
      ]);

      assert.equal(res1.statusCode, 200);
      assert.equal(res2.statusCode, 200);

      const updatedReq = await findRequestById(req.id);
      assert.ok(updatedReq);
      assert.equal(updatedReq.status, 'ISSUED');
      assert.equal(updatedReq.items[0].fulfilledQuantity, 10);

      const comp = await findComponentById(item.id);
      assert.equal(comp?.availableQuantity, 10);
    });

    test('two parallel issueRequestTransaction direct calls with Promise.all decrements stock exactly once', async () => {
      const item = await createComponent({
        name: 'Direct Concurrent Issue Comp',
        totalQuantity: 20,
        availableQuantity: 20,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Direct Concurrent Issue Test',
        status: 'APPROVED',
        items: [{ componentId: item.id, quantity: 10 }],
      });

      await Promise.all([
        RequestService.issueRequestTransaction(req),
        RequestService.issueRequestTransaction(req),
      ]);

      const updatedReq = await findRequestById(req.id);
      assert.ok(updatedReq);
      assert.equal(updatedReq.status, 'ISSUED');
      assert.equal(updatedReq.items[0].fulfilledQuantity, 10);

      const comp = await findComponentById(item.id);
      assert.equal(comp?.availableQuantity, 10);
    });

    test('two parallel PUT /requests/:id full RETURNED increments stock exactly once', async () => {
      const item = await createComponent({
        name: 'Concurrent Full Return Comp',
        totalQuantity: 20,
        availableQuantity: 10,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Concurrent Full Return Test',
        status: 'ISSUED',
        items: [{ componentId: item.id, quantity: 10, fulfilledQuantity: 10 }],
      });

      const responses = await Promise.all([
        app.inject({
          method: 'PUT',
          url: `/requests/${req.id}`,
          headers: { authorization: `Bearer ${adminToken}` },
          payload: { status: 'RETURNED' },
        }),
        app.inject({
          method: 'PUT',
          url: `/requests/${req.id}`,
          headers: { authorization: `Bearer ${adminToken}` },
          payload: { status: 'RETURNED' },
        }),
      ]);

      for (const res of responses) {
        assert.ok(res.statusCode === 200 || res.statusCode === 400);
      }
      assert.ok(responses.some((res) => res.statusCode === 200));

      const updatedReq = await findRequestById(req.id);
      assert.ok(updatedReq);
      assert.equal(updatedReq.status, 'RETURNED');
      assert.equal(updatedReq.items[0].returnedQuantity, 10);

      const comp = await findComponentById(item.id);
      assert.equal(comp?.availableQuantity, 20);
    });

    test('two parallel PUT /requests/:id partial RETURNED accumulates returnedQuantity and increments stock properly', async () => {
      const item = await createComponent({
        name: 'Concurrent Partial Return Comp',
        totalQuantity: 20,
        availableQuantity: 10,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Concurrent Partial Return Test',
        status: 'ISSUED',
        items: [{ componentId: item.id, quantity: 10, fulfilledQuantity: 10 }],
      });

      const [res1, res2] = await Promise.all([
        app.inject({
          method: 'PUT',
          url: `/requests/${req.id}`,
          headers: { authorization: `Bearer ${adminToken}` },
          payload: {
            status: 'RETURNED',
            returnItems: [{ componentId: item.id, quantity: 5 }],
          },
        }),
        app.inject({
          method: 'PUT',
          url: `/requests/${req.id}`,
          headers: { authorization: `Bearer ${adminToken}` },
          payload: {
            status: 'RETURNED',
            returnItems: [{ componentId: item.id, quantity: 5 }],
          },
        }),
      ]);

      assert.equal(res1.statusCode, 200);
      assert.equal(res2.statusCode, 200);

      const updatedReq = await findRequestById(req.id);
      assert.ok(updatedReq);
      assert.equal(updatedReq.status, 'RETURNED');
      assert.equal(updatedReq.items[0].returnedQuantity, 10);

      const comp = await findComponentById(item.id);
      assert.equal(comp?.availableQuantity, 20);
    });

    test('two parallel returnRequestTransaction direct calls with Promise.all increments stock exactly once', async () => {
      const item = await createComponent({
        name: 'Direct Concurrent Return Comp',
        totalQuantity: 20,
        availableQuantity: 10,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Direct Concurrent Return Test',
        status: 'ISSUED',
        items: [{ componentId: item.id, quantity: 10, fulfilledQuantity: 10 }],
      });

      await Promise.all([
        RequestService.returnRequestTransaction(req),
        RequestService.returnRequestTransaction(req),
      ]);

      const updatedReq = await findRequestById(req.id);
      assert.ok(updatedReq);
      assert.equal(updatedReq.status, 'RETURNED');
      assert.equal(updatedReq.items[0].returnedQuantity, 10);

      const comp = await findComponentById(item.id);
      assert.equal(comp?.availableQuantity, 20);
    });
  });

  describe('DELETE /requests/:id - Retract/Delete request', () => {
    test('admin can delete any pending request', async () => {
      const item = await createComponent({
        name: 'Sensor',
        totalQuantity: 10,
        availableQuantity: 10,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'Admin Delete Test',
        status: 'PENDING',
        items: [{ componentId: item.id, quantity: 1 }],
      });

      const response = await app.inject({
        method: 'DELETE',
        url: `/requests/${req.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
      });

      assert.equal(response.statusCode, 204);
      const exists = await findRequestById(req.id);
      assert.equal(exists, null);
    });
  });

  describe('OTP Generation & Lookup', () => {
    test('generateRequestOtp creates a 4-digit numeric OTP and returns expiration', () => {
      const requestId = randomUUID();
      const res = RequestService.generateRequestOtp(requestId);

      assert.ok(res.otp);
      assert.equal(/^\d{4}$/.test(res.otp), true);
      assert.ok(res.expiresAt);

      const secondRes = RequestService.generateRequestOtp(requestId);
      assert.equal(secondRes.otp, res.otp);
    });

    test('POST /requests/:id/otp generates OTP for valid request', async () => {
      const item = await createComponent({
        name: 'OTP Component',
        totalQuantity: 5,
        availableQuantity: 5,
      });
      createdComponentIds.push(item.id);

      const req = await createRequest({
        userId: studentId,
        targetFacultyId: facultyId,
        projectTitle: 'OTP Test Request',
        status: 'APPROVED',
        items: [{ componentId: item.id, quantity: 1 }],
      });

      const response = await app.inject({
        method: 'POST',
        url: `/requests/${req.id}/otp`,
        headers: { authorization: `Bearer ${studentToken}` },
      });

      assert.equal(response.statusCode, 200);
      const json = response.json();
      assert.ok(json.otp);
      assert.equal(/^\d{4}$/.test(json.otp), true);

      const lookupResponse = await app.inject({
        method: 'GET',
        url: `/requests/${json.otp}`,
        headers: { authorization: `Bearer ${adminToken}` },
      });

      assert.equal(lookupResponse.statusCode, 200);
      const lookupJson = lookupResponse.json();
      assert.equal(lookupJson.request.id, req.id);
      assert.equal(lookupJson.request.projectTitle, 'OTP Test Request');
    });

    test('consumeOtp and invalidateOtpForRequest clear the mapping', () => {
      const reqId = randomUUID();
      const res = RequestService.generateRequestOtp(reqId);

      assert.equal(RequestService.consumeOtp(res.otp), reqId);
      assert.equal(RequestService.consumeOtp(res.otp), null);

      const reqId2 = randomUUID();
      const res2 = RequestService.generateRequestOtp(reqId2);
      RequestService.invalidateOtpForRequest(reqId2);
      assert.equal(RequestService.consumeOtp(res2.otp), null);
    });
  });
});

describe('Write-off and reclaim', () => {
  const setOverdue = (requestId: string) =>
    db
      .update(request)
      .set({ returnDueAt: new Date(Date.now() - 60_000).toISOString() })
      .where(eq(request.id, requestId));

  test('partial return after expiry reclaims only the quantity that came back', async () => {
    const item = await createComponent({
      name: 'Reclaim Partial Sensor',
      totalQuantity: 10,
      availableQuantity: 5,
    });
    createdComponentIds.push(item.id);
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Reclaim Partial',
      status: 'ISSUED',
      items: [{ componentId: item.id, quantity: 5, fulfilledQuantity: 5 }],
    });
    await setOverdue(req.id);
    await expireOverdueRequests();

    let comp = await findComponentById(item.id);
    assert.equal(comp?.totalQuantity, 5);
    assert.equal(comp?.availableQuantity, 5);

    // 3 of the 5 written-off units come back.
    await RequestService.returnRequestTransaction({ id: req.id }, [
      { componentId: item.id, quantity: 3 },
    ]);

    comp = await findComponentById(item.id);
    assert.equal(comp?.availableQuantity, 8);
    assert.equal(comp?.totalQuantity, 8);
    let itemRow = (await findRequestById(req.id))?.items[0];
    assert.equal(itemRow?.returnedQuantity, 3);
    assert.equal(itemRow?.writtenOffQuantity, 2);

    // The remaining 2 arrive later: full round trip back to the original state.
    await RequestService.returnRequestTransaction({ id: req.id }, [
      { componentId: item.id, quantity: 2 },
    ]);

    comp = await findComponentById(item.id);
    assert.equal(comp?.availableQuantity, 10);
    assert.equal(comp?.totalQuantity, 10);
    itemRow = (await findRequestById(req.id))?.items[0];
    assert.equal(itemRow?.returnedQuantity, 5);
    assert.equal(itemRow?.writtenOffQuantity, 0);
  });

  test('returning stock that was never written off does not inflate the total', async () => {
    const item = await createComponent({
      name: 'No Reclaim Sensor',
      totalQuantity: 10,
      availableQuantity: 6,
    });
    createdComponentIds.push(item.id);
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'No Reclaim',
      status: 'ISSUED',
      items: [{ componentId: item.id, quantity: 4, fulfilledQuantity: 4 }],
    });

    await RequestService.returnRequestTransaction({ id: req.id }, [
      { componentId: item.id, quantity: 4 },
    ]);

    const comp = await findComponentById(item.id);
    assert.equal(comp?.availableQuantity, 10);
    assert.equal(comp?.totalQuantity, 10);
    assert.equal((await findRequestById(req.id))?.items[0].writtenOffQuantity, 0);
  });

  test('returning more than the outstanding quantity is rejected after expiry', async () => {
    const item = await createComponent({
      name: 'Over Reclaim Sensor',
      totalQuantity: 10,
      availableQuantity: 7,
    });
    createdComponentIds.push(item.id);
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Over Reclaim',
      status: 'ISSUED',
      items: [{ componentId: item.id, quantity: 3, fulfilledQuantity: 3 }],
    });
    await setOverdue(req.id);
    await expireOverdueRequests();

    await assert.rejects(
      () =>
        RequestService.returnRequestTransaction({ id: req.id }, [
          { componentId: item.id, quantity: 4 },
        ]),
      /excess/i,
    );

    const comp = await findComponentById(item.id);
    assert.equal(comp?.totalQuantity, 7);
    assert.equal(comp?.availableQuantity, 7);
  });
});

describe('Expired partial return via API', () => {
  test('returnItems are honoured and the request stays EXPIRED', async () => {
    const item = await createComponent({
      name: 'Expired Partial API Sensor',
      totalQuantity: 10,
      availableQuantity: 4,
    });
    createdComponentIds.push(item.id);
    const req = await createRequest({
      userId: studentId,
      targetFacultyId: facultyId,
      projectTitle: 'Expired Partial API',
      status: 'ISSUED',
      items: [{ componentId: item.id, quantity: 6, fulfilledQuantity: 6 }],
    });
    await db
      .update(request)
      .set({ returnDueAt: new Date(Date.now() - 60_000).toISOString() })
      .where(eq(request.id, req.id));
    await expireOverdueRequests();

    const response = await app.inject({
      method: 'PUT',
      url: `/requests/${req.id}`,
      headers: { authorization: `Bearer ${adminToken}` },
      payload: {
        status: 'RETURNED',
        returnItems: [{ componentId: item.id, quantity: 4 }],
      },
    });

    assert.equal(response.statusCode, 200);
    assert.equal(response.json().request.status, 'EXPIRED');

    const itemRow = (await findRequestById(req.id))?.items[0];
    assert.equal(itemRow?.returnedQuantity, 4);
    // 6 written off, 4 of them came back, 2 stay lost.
    assert.equal(itemRow?.writtenOffQuantity, 2);

    const comp = await findComponentById(item.id);
    assert.equal(comp?.availableQuantity, 8);
    assert.equal(comp?.totalQuantity, 8);
  });
});
