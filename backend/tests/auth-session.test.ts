import './test-setup.js';

import { describe, test, beforeAll, afterAll } from 'bun:test';
import assert from 'node:assert/strict';
import { hash } from 'bcryptjs';
import { buildApp } from '../src/app.js';
import { createUser, deleteUsers, UserRole } from './helpers.js';

let app: Awaited<ReturnType<typeof buildApp>>;
let adminEmail: string;
const createdUserIds: string[] = [];

const SESSION_COOKIE = 'iiitnr_session';

beforeAll(async () => {
  app = await buildApp();
  adminEmail = `session_${crypto.randomUUID()}@example.com`;
  const admin = await createUser({
    email: adminEmail,
    passwordHash: await hash('password123', 12),
    name: 'Session Admin',
    role: UserRole.ADMIN,
  });
  createdUserIds.push(admin.id);
});

afterAll(async () => {
  await deleteUsers(createdUserIds);
  await app.close();
});

function setCookieHeaders(response: { headers: Record<string, unknown> }): string[] {
  const raw = response.headers['set-cookie'];
  if (!raw) return [];
  return Array.isArray(raw) ? raw.map(String) : [String(raw)];
}

function sessionCookiePair(response: { headers: Record<string, unknown> }): string {
  const header = setCookieHeaders(response).find((cookie) =>
    cookie.startsWith(`${SESSION_COOKIE}=`),
  );
  assert.ok(header, 'expected a session cookie in the response');
  return header.split(';')[0] as string;
}

async function login() {
  const response = await app.inject({
    method: 'POST',
    url: '/auth/login',
    payload: { email: adminEmail, password: 'password123' },
  });
  assert.equal(response.statusCode, 200);
  return response;
}

describe('Web session cookie', () => {
  test('login sets an httpOnly, strict session cookie', async () => {
    const response = await login();
    const cookie = setCookieHeaders(response).find((value) =>
      value.startsWith(`${SESSION_COOKIE}=`),
    );

    assert.ok(cookie, 'expected a session cookie');
    assert.match(cookie, /HttpOnly/i);
    assert.match(cookie, /SameSite=Strict/i);
    assert.match(cookie, /Path=\//);
    assert.equal(response.json().token.length > 0, true);
  });

  test('the session cookie alone authenticates a protected route', async () => {
    const cookie = sessionCookiePair(await login());

    const meResponse = await app.inject({
      method: 'GET',
      url: '/auth/me',
      headers: { cookie },
    });

    assert.equal(meResponse.statusCode, 200);
    assert.equal(meResponse.json().user.email, adminEmail);
  });

  test('a request with no bearer and no cookie stays unauthorized', async () => {
    const response = await app.inject({ method: 'GET', url: '/auth/me' });
    assert.equal(response.statusCode, 401);
  });

  test('POST /auth/session re-mints a token from the cookie alone', async () => {
    const cookie = sessionCookiePair(await login());

    const response = await app.inject({
      method: 'POST',
      url: '/auth/session',
      headers: { cookie },
    });

    assert.equal(response.statusCode, 200);
    const body = response.json();
    assert.equal(body.user.email, adminEmail);
    assert.equal(body.user.role, UserRole.ADMIN);
    assert.ok(body.token);

    // The re-minted token works on its own, with no cookie.
    const meResponse = await app.inject({
      method: 'GET',
      url: '/auth/me',
      headers: { authorization: `Bearer ${body.token}` },
    });
    assert.equal(meResponse.statusCode, 200);

    // ...and the cookie is refreshed alongside it.
    assert.ok(sessionCookiePair(response).startsWith(`${SESSION_COOKIE}=`));
  });

  test('POST /auth/session also accepts a Bearer token', async () => {
    // Not a migration path: any authenticated route takes the header, and native
    // clients keep using it. This pins that `/auth/session` is no exception.
    const loginResponse = await login();
    const bearerToken = loginResponse.json().token as string;

    const response = await app.inject({
      method: 'POST',
      url: '/auth/session',
      headers: { authorization: `Bearer ${bearerToken}` },
    });

    assert.equal(response.statusCode, 200);
    assert.equal(response.json().user.email, adminEmail);
    assert.ok(sessionCookiePair(response), 'expected the call to set the cookie');
  });

  test('POST /auth/session without a valid session is 401', async () => {
    const response = await app.inject({ method: 'POST', url: '/auth/session' });
    assert.equal(response.statusCode, 401);
  });

  test('POST /auth/logout clears the cookie', async () => {
    const response = await app.inject({ method: 'POST', url: '/auth/logout' });

    assert.equal(response.statusCode, 204);
    const cleared = setCookieHeaders(response).find((value) =>
      value.startsWith(`${SESSION_COOKIE}=`),
    );
    assert.ok(cleared, 'expected the session cookie to be cleared');
    assert.match(cleared, /Expires=Thu, 01 Jan 1970|Max-Age=0/i);
  });
});
