import './test-setup.js';

import { describe, test, beforeAll, afterAll, beforeEach } from 'bun:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { buildApp } from '../src/app.js';
import {
  createComponent,
  createUser,
  deleteAllRequests,
  deleteComponents,
  deleteUsers,
  UserRole,
} from './helpers.js';

let app: Awaited<ReturnType<typeof buildApp>>;
let studentToken: string;
let studentId: string;
let facultyToken: string;
let facultyId: string;
let adminUserId: string;
let componentId: string;
const createdUserIds: string[] = [];
const createdComponentIds: string[] = [];

const auth = (token: string) => ({ headers: { authorization: `Bearer ${token}` } });

async function listNotifications(token: string) {
  const response = await app.inject({
    method: 'GET',
    url: '/notifications',
    ...auth(token),
  });
  assert.equal(response.statusCode, 200);
  return response.json() as {
    notifications: {
      id: string;
      type: string;
      title: string;
      body: string | null;
      requestId: string | null;
      read: boolean;
    }[];
    unreadCount: number;
  };
}

beforeAll(async () => {
  app = await buildApp();

  const suffix = randomUUID();
  const passwordHash = 'not-used-for-inject';

  const studentUser = await createUser({
    email: `notif-student_${suffix}@example.com`,
    passwordHash,
    name: 'Notif Student',
    role: UserRole.STUDENT,
  });
  studentId = studentUser.id;
  studentToken = app.jwt.sign({ sub: studentUser.id, role: studentUser.role }, { expiresIn: '1h' });

  const facultyUser = await createUser({
    email: `notif-faculty_${suffix}@example.com`,
    passwordHash,
    name: 'Notif Faculty',
    role: UserRole.FACULTY,
  });
  facultyId = facultyUser.id;
  facultyToken = app.jwt.sign({ sub: facultyUser.id, role: facultyUser.role }, { expiresIn: '1h' });

  const adminUser = await createUser({
    email: `notif-admin_${suffix}@example.com`,
    passwordHash,
    name: 'Notif Admin',
    role: UserRole.ADMIN,
  });
  adminUserId = adminUser.id;

  createdUserIds.push(studentId, facultyId, adminUserId);

  const comp = await createComponent({
    name: `Notif Sensor_${suffix}`,
    totalQuantity: 5,
  });
  componentId = comp.id;
  createdComponentIds.push(comp.id);
});

afterAll(async () => {
  await deleteAllRequests();
  await deleteComponents(createdComponentIds);
  await deleteUsers(createdUserIds);
  await app.close();
});

beforeEach(async () => {
  await deleteAllRequests();
});

describe('request submission notifications', () => {
  test('creating a request notifies target faculty and staff, not the actor', async () => {
    const response = await app.inject({
      method: 'POST',
      url: '/requests',
      ...auth(studentToken),
      payload: {
        targetFacultyId: facultyId,
        projectTitle: 'Notification Test Project',
        items: [{ componentId, quantity: 2 }],
      },
    });
    assert.equal(response.statusCode, 201);

    const facultyInbox = await listNotifications(facultyToken);
    assert.equal(facultyInbox.unreadCount, 1);
    assert.equal(facultyInbox.notifications[0]?.type, 'REQUEST_SUBMITTED');
    assert.match(facultyInbox.notifications[0]?.title ?? '', /awaiting your approval/i);

    const studentInbox = await listNotifications(studentToken);
    assert.equal(studentInbox.unreadCount, 0);

    const adminInbox = await listNotifications(
      app.jwt.sign({ sub: adminUserId, role: UserRole.ADMIN }, { expiresIn: '1h' }),
    );
    assert.equal(adminInbox.notifications[0]?.type, 'REQUEST_SUBMITTED');
  });
});

describe('request approval notifications', () => {
  test('faculty approval notifies the student, and not the approving faculty', async () => {
    const created = await app.inject({
      method: 'POST',
      url: '/requests',
      ...auth(studentToken),
      payload: {
        targetFacultyId: facultyId,
        projectTitle: 'Approval Notify Project',
        items: [{ componentId, quantity: 1 }],
      },
    });
    assert.equal(created.statusCode, 201);
    const requestId = (created.json() as { request: { id: string } }).request.id;

    const approved = await app.inject({
      method: 'PUT',
      url: `/requests/${requestId}`,
      ...auth(facultyToken),
      payload: { status: 'APPROVED' },
    });
    assert.equal(approved.statusCode, 200);

    const studentInbox = await listNotifications(studentToken);
    assert.equal(studentInbox.unreadCount, 1);
    assert.equal(studentInbox.notifications[0]?.type, 'REQUEST_APPROVED');
    assert.equal(studentInbox.notifications[0]?.requestId, requestId);

    const facultyInbox = await listNotifications(facultyToken);
    const approvalNotifs = facultyInbox.notifications.filter((n) => n.type === 'REQUEST_APPROVED');
    assert.equal(approvalNotifs.length, 0);
  });
});

describe('renewal notifications', () => {
  test('renewal request notifies faculty, renewal approval notifies student', async () => {
    const created = await app.inject({
      method: 'POST',
      url: '/requests',
      ...auth(studentToken),
      payload: {
        targetFacultyId: facultyId,
        projectTitle: 'Renewal Notify Project',
        items: [{ componentId, quantity: 1 }],
      },
    });
    const requestId = (created.json() as { request: { id: string } }).request.id;

    await app.inject({
      method: 'PUT',
      url: `/requests/${requestId}`,
      ...auth(facultyToken),
      payload: { status: 'APPROVED' },
    });
    await app.inject({
      method: 'PUT',
      url: `/requests/${requestId}`,
      ...auth(app.jwt.sign({ sub: adminUserId, role: UserRole.ADMIN }, { expiresIn: '1h' })),
      payload: { status: 'ISSUED' },
    });

    const renewRequested = await app.inject({
      method: 'PUT',
      url: `/requests/${requestId}`,
      ...auth(studentToken),
      payload: { status: 'REQUESTED_RENEW', lastRenewReason: 'Testing renewals' },
    });
    assert.equal(renewRequested.statusCode, 200);

    const facultyInbox = await listNotifications(facultyToken);
    assert.equal(facultyInbox.notifications[0]?.type, 'RENEWAL_REQUESTED');

    const renewed = await app.inject({
      method: 'PUT',
      url: `/requests/${requestId}`,
      ...auth(facultyToken),
      payload: { status: 'RENEWED' },
    });
    assert.equal(renewed.statusCode, 200);

    const studentInbox = await listNotifications(studentToken);
    assert.equal(studentInbox.notifications[0]?.type, 'RENEWAL_APPROVED');
  });

  test('rejecting a renewal notifies the student that it was rejected', async () => {
    const created = await app.inject({
      method: 'POST',
      url: '/requests',
      ...auth(studentToken),
      payload: {
        targetFacultyId: facultyId,
        projectTitle: 'Rejected Renewal Project',
        items: [{ componentId, quantity: 1 }],
      },
    });
    const requestId = (created.json() as { request: { id: string } }).request.id;

    await app.inject({
      method: 'PUT',
      url: `/requests/${requestId}`,
      ...auth(facultyToken),
      payload: { status: 'APPROVED' },
    });
    await app.inject({
      method: 'PUT',
      url: `/requests/${requestId}`,
      ...auth(app.jwt.sign({ sub: adminUserId, role: UserRole.ADMIN }, { expiresIn: '1h' })),
      payload: { status: 'ISSUED' },
    });
    await app.inject({
      method: 'PUT',
      url: `/requests/${requestId}`,
      ...auth(studentToken),
      payload: { status: 'REQUESTED_RENEW', lastRenewReason: 'Testing rejections' },
    });

    const rejected = await app.inject({
      method: 'PUT',
      url: `/requests/${requestId}`,
      ...auth(facultyToken),
      payload: { status: 'ISSUED' },
    });
    assert.equal(rejected.statusCode, 200);

    const studentInbox = await listNotifications(studentToken);
    const rejection = studentInbox.notifications.find((n) => n.type === 'RENEWAL_REJECTED');
    assert.ok(rejection);
    assert.equal(rejection?.requestId, requestId);
  });
});

describe('notification read state', () => {
  test('mark one and mark-all read endpoints work', async () => {
    await app.inject({
      method: 'POST',
      url: '/requests',
      ...auth(studentToken),
      payload: {
        targetFacultyId: facultyId,
        projectTitle: 'Read State Project',
        items: [{ componentId, quantity: 1 }],
      },
    });

    const inbox = await listNotifications(facultyToken);
    assert.equal(inbox.unreadCount, 1);

    const notificationId = inbox.notifications[0]?.id;
    assert.ok(notificationId);

    const marked = await app.inject({
      method: 'PUT',
      url: `/notifications/${notificationId}/read`,
      ...auth(facultyToken),
    });
    assert.equal(marked.statusCode, 200);

    const afterMark = await listNotifications(facultyToken);
    assert.equal(afterMark.unreadCount, 0);
    assert.equal(afterMark.notifications[0]?.read, true);
  });

  test('cannot mark another user notification as read', async () => {
    await app.inject({
      method: 'POST',
      url: '/requests',
      ...auth(studentToken),
      payload: {
        targetFacultyId: facultyId,
        projectTitle: 'Cross User Project',
        items: [{ componentId, quantity: 1 }],
      },
    });

    const facultyInbox = await listNotifications(facultyToken);
    const notificationId = facultyInbox.notifications[0]?.id;
    assert.ok(notificationId);

    const stolen = await app.inject({
      method: 'PUT',
      url: `/notifications/${notificationId}/read`,
      ...auth(studentToken),
    });
    assert.equal(stolen.statusCode, 404);
  });

  test('mark-all-read clears the badge', async () => {
    await app.inject({
      method: 'POST',
      url: '/requests',
      ...auth(studentToken),
      payload: {
        targetFacultyId: facultyId,
        projectTitle: 'Mark All Project 1',
        items: [{ componentId, quantity: 1 }],
      },
    });
    await app.inject({
      method: 'POST',
      url: '/requests',
      ...auth(studentToken),
      payload: {
        targetFacultyId: facultyId,
        projectTitle: 'Mark All Project 2',
        items: [{ componentId, quantity: 1 }],
      },
    });

    const inbox = await listNotifications(facultyToken);
    assert.equal(inbox.unreadCount, 2);

    const marked = await app.inject({
      method: 'PUT',
      url: '/notifications/read-all',
      ...auth(facultyToken),
    });
    assert.equal(marked.statusCode, 200);

    const after = await listNotifications(facultyToken);
    assert.equal(after.unreadCount, 0);
  });
});

describe('notification auth', () => {
  test('requires auth', async () => {
    const response = await app.inject({ method: 'GET', url: '/notifications' });
    assert.equal(response.statusCode, 401);
  });
});
