import './test-setup.js';

import { describe, test, beforeAll, afterAll } from 'bun:test';
import assert from 'node:assert/strict';
import { hash } from 'bcryptjs';
import { buildApp } from '../src/app.js';
import { createUser, deleteUsers, UserRole } from './helpers.js';

const ALLOWED_ORIGIN = 'https://inventory.example.edu';
const EVIL_ORIGIN = 'https://students.example.edu';

let app: Awaited<ReturnType<typeof buildApp>>;
let previousAllowedOrigins: string | undefined;
const createdUserIds: string[] = [];

beforeAll(async () => {
  // The guard mirrors the CORS allow-list, so it is only active once one exists.
  previousAllowedOrigins = process.env.ALLOWED_ORIGINS;
  process.env.ALLOWED_ORIGINS = ALLOWED_ORIGIN;

  app = await buildApp();
  const user = await createUser({
    email: `cookie_origin_${crypto.randomUUID()}@example.com`,
    passwordHash: await hash('password123', 12),
    role: UserRole.ADMIN,
  });
  createdUserIds.push(user.id);
});

afterAll(async () => {
  if (previousAllowedOrigins === undefined) {
    delete process.env.ALLOWED_ORIGINS;
  } else {
    process.env.ALLOWED_ORIGINS = previousAllowedOrigins;
  }
  await deleteUsers(createdUserIds);
  await app.close();
});

async function sessionCookie(): Promise<string> {
  const email = `cookie_origin_${crypto.randomUUID()}@example.com`;
  const created = await createUser({
    email,
    passwordHash: await hash('password123', 12),
    role: UserRole.ADMIN,
  });
  createdUserIds.push(created.id);

  const login = await app.inject({
    method: 'POST',
    url: '/auth/login',
    payload: { email, password: 'password123' },
  });
  assert.equal(login.statusCode, 200);

  const raw = login.headers['set-cookie'];
  const header = (Array.isArray(raw) ? raw.map(String) : [String(raw)]).find((value) =>
    value.startsWith('iiitnr_session='),
  );
  assert.ok(header, 'expected a session cookie');
  return header.split(';')[0] as string;
}

describe('Cookie-authenticated CSRF guard', () => {
  test('a cookie POST from a foreign origin is rejected', async () => {
    const cookie = await sessionCookie();

    const response = await app.inject({
      method: 'POST',
      url: '/auth/logout',
      headers: { cookie, origin: EVIL_ORIGIN },
    });

    assert.equal(response.statusCode, 403);
  });

  test('a cookie POST from an allowed origin passes', async () => {
    const cookie = await sessionCookie();

    const response = await app.inject({
      method: 'POST',
      url: '/auth/logout',
      headers: { cookie, origin: ALLOWED_ORIGIN },
    });

    assert.equal(response.statusCode, 204);
  });

  test('a Bearer POST from a foreign origin is not blocked', async () => {
    // Bearer tokens are not ambient credentials, so CSRF does not apply.
    const cookie = await sessionCookie();
    const response = await app.inject({
      method: 'POST',
      url: '/auth/logout',
      headers: { cookie, origin: EVIL_ORIGIN, authorization: 'Bearer not-a-real-token' },
    });

    assert.notEqual(response.statusCode, 403);
  });

  test('a read request is not blocked regardless of origin', async () => {
    const cookie = await sessionCookie();

    const response = await app.inject({
      method: 'GET',
      url: '/auth/me',
      headers: { cookie, origin: EVIL_ORIGIN },
    });

    assert.equal(response.statusCode, 200);
  });

  test('a request with no Origin (native client) is not blocked', async () => {
    const cookie = await sessionCookie();

    const response = await app.inject({
      method: 'POST',
      url: '/auth/logout',
      headers: { cookie },
    });

    assert.equal(response.statusCode, 204);
  });
});
