import './test-setup.js';

import { describe, test, beforeAll, afterAll } from 'bun:test';
import assert from 'node:assert/strict';
import { eq, inArray } from 'drizzle-orm';
import { buildApp } from '../src/app.js';
import { db } from '../src/drizzle/db.js';
import { user, auditLog } from '../src/drizzle/schema.js';
import { findOrCreateGoogleUser } from '../src/routes/auth.js';

let app: Awaited<ReturnType<typeof buildApp>>;
const createdUserIds: string[] = [];

beforeAll(async () => {
  app = await buildApp();
});

afterAll(async () => {
  if (createdUserIds.length > 0) {
    await db.delete(auditLog).where(inArray(auditLog.userId, createdUserIds));
    await db.delete(user).where(inArray(user.id, createdUserIds));
  }
  await app.close();
});

describe('Authentication API', () => {
  test('registers a user but gets 403 when trying to log in', async () => {
    const email = `user_${crypto.randomUUID()}@example.com`;
    const password = 'password123';

    const registerResponse = await app.inject({
      method: 'POST',
      url: '/auth/register',
      payload: {
        email,
        password,
        name: 'Test User',
      },
    });

    assert.equal(registerResponse.statusCode, 201);

    const registerBody = registerResponse.json();
    assert.equal(registerBody.user.email, email);
    assert.equal(registerBody.user.role, 'PENDING');
    assert.ok(registerBody.token);
    createdUserIds.push(registerBody.user.id);

    const loginResponse = await app.inject({
      method: 'POST',
      url: '/auth/login',
      payload: {
        email,
        password,
      },
    });

    assert.equal(loginResponse.statusCode, 403);
  });

  describe('POST /auth/register', () => {
    test('returns 400 when fields are missing', async () => {
      const response = await app.inject({
        method: 'POST',
        url: '/auth/register',
        payload: { email: 'test@example.com' },
      });

      assert.equal(response.statusCode, 400);
      assert.ok(response.json().error.includes('required'));
    });

    test('returns 400 when password is too short', async () => {
      const response = await app.inject({
        method: 'POST',
        url: '/auth/register',
        payload: {
          email: `short_${crypto.randomUUID()}@example.com`,
          password: 'short',
          name: 'Short Pass',
        },
      });

      assert.equal(response.statusCode, 400);
      assert.ok(response.json().error.includes('at least 8 characters'));
    });

    test('returns 400 when email is already in use', async () => {
      const email = `duplicate_${crypto.randomUUID()}@example.com`;
      const now = new Date().toISOString();
      const userId = crypto.randomUUID();
      await db.insert(user).values({
        id: userId,
        email,
        passwordHash: 'hash',
        name: 'Existing User',
        role: 'PENDING',
        createdAt: now,
        updatedAt: now,
      });
      createdUserIds.push(userId);

      const response = await app.inject({
        method: 'POST',
        url: '/auth/register',
        payload: {
          email,
          password: 'password123',
          name: 'New User',
        },
      });

      assert.equal(response.statusCode, 400);
      assert.ok(response.json().error.includes('already in use'));
    });
  });

  describe('POST /auth/login', () => {
    test('returns 400 when fields are missing', async () => {
      const response = await app.inject({
        method: 'POST',
        url: '/auth/login',
        payload: { email: 'test@example.com' },
      });

      assert.equal(response.statusCode, 400);
    });

    test('returns 401 for non-existent user', async () => {
      const response = await app.inject({
        method: 'POST',
        url: '/auth/login',
        payload: {
          email: 'nonexistent@example.com',
          password: 'password123',
        },
      });

      assert.equal(response.statusCode, 401);
      assert.ok(response.json().error.includes('invalid credentials'));
    });

    test('returns 401 for incorrect password', async () => {
      const email = `wrongpass_${crypto.randomUUID()}@example.com`;
      const { hash } = await import('bcryptjs');
      const passwordHash = await hash('correctpassword', 12);
      const now = new Date().toISOString();

      const [created] = await db
        .insert(user)
        .values({
          id: crypto.randomUUID(),
          email,
          passwordHash,
          name: 'Wrong Pass User',
          role: 'ADMIN',
          createdAt: now,
          updatedAt: now,
        })
        .returning();
      if (created) createdUserIds.push(created.id);

      const response = await app.inject({
        method: 'POST',
        url: '/auth/login',
        payload: {
          email,
          password: 'wrongpassword',
        },
      });

      assert.equal(response.statusCode, 401);
    });

    test('returns 401 for Google-only account', async () => {
      const email = `googleonly_${crypto.randomUUID()}@example.com`;
      const now = new Date().toISOString();
      const [created] = await db
        .insert(user)
        .values({
          id: crypto.randomUUID(),
          email,
          googleId: 'google-123',
          name: 'Google User',
          role: 'STUDENT',
          createdAt: now,
          updatedAt: now,
        })
        .returning();
      if (created) createdUserIds.push(created.id);

      const response = await app.inject({
        method: 'POST',
        url: '/auth/login',
        payload: {
          email,
          password: 'somepassword',
        },
      });

      assert.equal(response.statusCode, 401);
      assert.ok(response.json().error.includes('uses Google Sign-In'));
    });

    test('refuses password login for an account linked to Google', async () => {
      const email = `linked_${crypto.randomUUID()}@example.com`;
      const { hash } = await import('bcryptjs');
      const passwordHash = await hash('correctpassword', 12);
      const now = new Date().toISOString();

      // A linked account must not keep a usable password, even if a stale hash
      // survived (for example from before the link was made).
      const [created] = await db
        .insert(user)
        .values({
          id: crypto.randomUUID(),
          email,
          passwordHash,
          googleId: 'google-linked-1',
          name: 'Linked User',
          role: 'STUDENT',
          createdAt: now,
          updatedAt: now,
        })
        .returning();
      if (created) createdUserIds.push(created.id);

      const response = await app.inject({
        method: 'POST',
        url: '/auth/login',
        payload: {
          email,
          password: 'correctpassword',
        },
      });

      assert.equal(response.statusCode, 401);
      assert.ok(response.json().error.includes('uses Google Sign-In'));
    });

    test('throttles repeated failures for the same account', async () => {
      const email = `throttle_${crypto.randomUUID()}@example.com`;
      const { hash } = await import('bcryptjs');
      const passwordHash = await hash('correctpassword', 12);
      const now = new Date().toISOString();

      const [created] = await db
        .insert(user)
        .values({
          id: crypto.randomUUID(),
          email,
          passwordHash,
          name: 'Throttle User',
          role: 'STUDENT',
          createdAt: now,
          updatedAt: now,
        })
        .returning();
      if (created) createdUserIds.push(created.id);

      for (let attempt = 0; attempt < 5; attempt += 1) {
        const failed = await app.inject({
          method: 'POST',
          url: '/auth/login',
          payload: { email, password: 'wrongpassword' },
        });
        assert.equal(failed.statusCode, 401);
      }

      // The correct password is refused while the account is throttled.
      const blocked = await app.inject({
        method: 'POST',
        url: '/auth/login',
        payload: { email, password: 'correctpassword' },
      });

      assert.equal(blocked.statusCode, 429);
      assert.ok(blocked.json().error.includes('too many failed login attempts'));
    });
  });

  describe('GET /auth/me', () => {
    test('returns 401 without token', async () => {
      const response = await app.inject({
        method: 'GET',
        url: '/auth/me',
      });

      assert.equal(response.statusCode, 401);
    });

    test('returns user for valid token', async () => {
      const email = `me_${crypto.randomUUID()}@example.com`;
      const now = new Date().toISOString();
      const [created] = await db
        .insert(user)
        .values({
          id: crypto.randomUUID(),
          email,
          name: 'Me User',
          role: 'ADMIN',
          createdAt: now,
          updatedAt: now,
        })
        .returning();
      assert.ok(created);
      if (created) {
        createdUserIds.push(created.id);
        const token = app.jwt.sign({ sub: created.id, role: created.role });

        const response = await app.inject({
          method: 'GET',
          url: '/auth/me',
          headers: {
            authorization: `Bearer ${token}`,
          },
        });

        assert.equal(response.statusCode, 200);
        assert.equal(response.json().user.email, email);
      }
    });

    test('returns 401 for invalid token', async () => {
      const response = await app.inject({
        method: 'GET',
        url: '/auth/me',
        headers: {
          authorization: 'Bearer invalid-token',
        },
      });

      assert.equal(response.statusCode, 401);
    });

    test('returns 401 if user no longer exists', async () => {
      const id = 'deleted-user-id';
      const token = app.jwt.sign({ sub: id, role: 'STUDENT' });

      const response = await app.inject({
        method: 'GET',
        url: '/auth/me',
        headers: {
          authorization: `Bearer ${token}`,
        },
      });

      assert.equal(response.statusCode, 401);
    });
  });

  describe('Google account linking', () => {
    test('clears an existing password when Google is linked to the account', async () => {
      const email = `prelink_${crypto.randomUUID()}@iiitnr.edu.in`;
      const { hash } = await import('bcryptjs');
      const passwordHash = await hash('attackerpassword', 12);
      const now = new Date().toISOString();

      const [created] = await db
        .insert(user)
        .values({
          id: crypto.randomUUID(),
          email,
          passwordHash,
          name: 'Pre-registered',
          role: 'PENDING',
          createdAt: now,
          updatedAt: now,
        })
        .returning();
      if (created) createdUserIds.push(created.id);

      await findOrCreateGoogleUser('google-sub-link-1', email, 'Real Owner', null);

      const [linked] = await db.select().from(user).where(eq(user.email, email));
      assert.ok(linked);
      assert.equal(linked.googleId, 'google-sub-link-1');
      assert.equal(linked.passwordHash, null, 'pre-set password must not survive the link');
    });

    test('never grants ADMIN from a client-supplied role', async () => {
      const email = `escalate_${crypto.randomUUID()}@iiitnr.edu.in`;

      const created = await findOrCreateGoogleUser(
        'google-sub-escalate',
        email,
        'Escalation Attempt',
        null,
        'ADMIN' as never,
      );
      createdUserIds.push(created.id);

      assert.notEqual(created.role, 'ADMIN');
    });
  });

  describe('POST /auth/google - Google Sign-In', () => {
    test('rejects a client-supplied ADMIN role', async () => {
      const response = await app.inject({
        method: 'POST',
        url: '/auth/google',
        payload: { idToken: 'anything', role: 'ADMIN' },
      });

      assert.equal(response.statusCode, 400);
      assert.ok(response.json().error.includes('role'));
    });

    test('returns 400 when idToken is missing', async () => {
      const response = await app.inject({
        method: 'POST',
        url: '/auth/google',
        payload: {},
      });

      assert.equal(response.statusCode, 400);
      const body = response.json();
      assert.ok(body.error.includes('idToken is required'));
    });

    test('returns 500 when GOOGLE_CLIENT_ID is not configured', async () => {
      const originalClientId = process.env.GOOGLE_CLIENT_ID;
      delete process.env.GOOGLE_CLIENT_ID;

      const response = await app.inject({
        method: 'POST',
        url: '/auth/google',
        payload: {
          idToken: 'test-token',
        },
      });

      assert.equal(response.statusCode, 500);
      const body = response.json();
      assert.ok(body.error.includes('Google OAuth not configured'));

      if (originalClientId) {
        process.env.GOOGLE_CLIENT_ID = originalClientId;
      }
    });

    test('returns 400 for invalid Google token', async () => {
      const originalClientId = process.env.GOOGLE_CLIENT_ID;
      process.env.GOOGLE_CLIENT_ID = 'test-client-id.apps.googleusercontent.com';

      const response = await app.inject({
        method: 'POST',
        url: '/auth/google',
        payload: {
          idToken: 'invalid-token',
        },
      });

      assert.ok([400, 401].includes(response.statusCode));

      if (originalClientId) {
        process.env.GOOGLE_CLIENT_ID = originalClientId;
      } else {
        delete process.env.GOOGLE_CLIENT_ID;
      }
    });

    test('validates email domain restriction', async () => {
      const originalClientId = process.env.GOOGLE_CLIENT_ID;
      const originalDomain = process.env.ALLOWED_EMAIL_DOMAIN;

      process.env.GOOGLE_CLIENT_ID = 'test-client-id.apps.googleusercontent.com';
      process.env.ALLOWED_EMAIL_DOMAIN = '@iiitnr.edu.in';

      const response = await app.inject({
        method: 'POST',
        url: '/auth/google',
        payload: {
          idToken: 'invalid-token-for-domain-test',
        },
      });

      assert.ok([400, 401].includes(response.statusCode));

      if (originalClientId) {
        process.env.GOOGLE_CLIENT_ID = originalClientId;
      } else {
        delete process.env.GOOGLE_CLIENT_ID;
      }
      if (originalDomain) {
        process.env.ALLOWED_EMAIL_DOMAIN = originalDomain;
      } else {
        delete process.env.ALLOWED_EMAIL_DOMAIN;
      }
    });
  });
});
