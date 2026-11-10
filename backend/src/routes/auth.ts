import type { FastifyPluginCallback, FastifyRequest, FastifyReply } from 'fastify';
import { compare, hash } from 'bcryptjs';
import { OAuth2Client } from 'google-auth-library';
import { eq } from 'drizzle-orm';
import { db } from '../drizzle/db.js';
import { user } from '../drizzle/schema.js';
import { UserRole, AuditActionType } from '../utils/enums.js';
import type { UserRoleValue } from '../utils/enums.js';
import { logAudit } from '../utils/audit.js';
import { sanitizeString } from '../utils/validation.js';
import { deriveIiitnrProfileFromEmail, pickDerivedProfileUpdates } from '../utils/iiitnr-email.js';

interface RegisterBody {
  email?: string;
  password?: string;
  name?: string;
}

interface LoginBody {
  email?: string;
  password?: string;
}

interface GoogleAuthBody {
  idToken?: string;
  role?: string;
}

interface UserResponse {
  id: string;
  email: string;
  name: string | null;
  imageUrl: string | null;
  role: string;
  batch: string | null;
  branch: string | null;
}

const userPublicColumns = {
  id: true,
  email: true,
  name: true,
  imageUrl: true,
  role: true,
  batch: true,
  branch: true,
} as const;

interface ValidationError {
  code: number;
  message: string;
}

interface GoogleClientIds {
  primary: string;
  all: string[];
}

interface GooglePayload {
  sub: string;
  email?: string;
  email_verified?: boolean;
  name?: string;
  given_name?: string;
  picture?: string;
}

const registerSchema = {
  body: {
    type: 'object',
    required: ['email', 'password'],
    properties: {
      email: { type: 'string', maxLength: 254 },
      password: { type: 'string', maxLength: 128 },
      name: { type: 'string', maxLength: 100 },
    },
    additionalProperties: false,
  },
} as const;

const loginSchema = {
  body: {
    type: 'object',
    required: ['email', 'password'],
    properties: {
      email: { type: 'string', maxLength: 254 },
      password: { type: 'string', maxLength: 128 },
    },
    additionalProperties: false,
  },
} as const;

// Roles a user may pick for themselves at Google sign-up. ADMIN is deliberately
// absent: elevation to admin must go through an existing admin.
const SELF_ASSIGNABLE_ROLES = [UserRole.STUDENT, UserRole.FACULTY, UserRole.LA] as const;

function resolveSelfAssignedRole(requestedRole?: UserRoleValue): UserRoleValue | undefined {
  if (!requestedRole) return undefined;
  return (SELF_ASSIGNABLE_ROLES as readonly UserRoleValue[]).includes(requestedRole)
    ? requestedRole
    : undefined;
}

const googleSchema = {
  body: {
    type: 'object',
    required: ['idToken'],
    properties: {
      idToken: { type: 'string', maxLength: 4096 },
      role: { type: 'string', enum: [...SELF_ASSIGNABLE_ROLES] },
    },
    additionalProperties: false,
  },
} as const;

// Per-account throttling on top of the per-IP rate limit: a distributed or
// slow-and-low attacker stays under the IP limit, so failures are also counted
// per (email, client IP). Keying on the pair keeps one attacker from locking out
// an account for everybody. In-memory and per-process, which is enough to slow
// online guessing well past what bcrypt cost 12 already costs.
const LOGIN_FAILURE_LIMIT = 5;
const LOGIN_FAILURE_WINDOW_MS = 10 * 60 * 1000;
const LOGIN_BLOCK_MS = 10 * 60 * 1000;
const LOGIN_THROTTLE_MAX_ENTRIES = 10_000;

interface LoginThrottleEntry {
  failures: number[];
  blockedUntil: number;
}

const loginThrottle = new Map<string, LoginThrottleEntry>();

function loginThrottleKey(email: string, clientIp: string): string {
  return `${email}|${clientIp}`;
}

function pruneLoginThrottle(now: number): void {
  if (loginThrottle.size <= LOGIN_THROTTLE_MAX_ENTRIES) return;
  for (const [key, entry] of loginThrottle.entries()) {
    const idle =
      entry.blockedUntil < now && entry.failures.every((at) => now - at >= LOGIN_FAILURE_WINDOW_MS);
    if (idle) loginThrottle.delete(key);
  }
}

function getLoginBlockRemainingMs(key: string): number {
  const entry = loginThrottle.get(key);
  if (!entry) return 0;

  const now = Date.now();
  if (entry.blockedUntil > now) return entry.blockedUntil - now;

  entry.failures = entry.failures.filter((at) => now - at < LOGIN_FAILURE_WINDOW_MS);
  if (entry.failures.length === 0) loginThrottle.delete(key);
  return 0;
}

function recordLoginFailure(key: string): void {
  const now = Date.now();
  const entry = loginThrottle.get(key) ?? { failures: [], blockedUntil: 0 };

  entry.failures = entry.failures.filter((at) => now - at < LOGIN_FAILURE_WINDOW_MS);
  entry.failures.push(now);

  if (entry.failures.length >= LOGIN_FAILURE_LIMIT) {
    entry.blockedUntil = now + LOGIN_BLOCK_MS;
    entry.failures = [];
  }

  loginThrottle.set(key, entry);
  pruneLoginThrottle(now);
}

function clearLoginFailures(key: string): void {
  loginThrottle.delete(key);
}

function getGoogleClientIds(): GoogleClientIds | null {
  const primaryClientId = process.env.GOOGLE_CLIENT_ID;
  if (!primaryClientId) return null;

  const extraClientIds =
    process.env.GOOGLE_CLIENT_EXTRA_IDS?.split(',')
      .map((id) => id.trim())
      .filter(Boolean) ?? [];

  return {
    primary: primaryClientId,
    all: [primaryClientId, ...extraClientIds],
  };
}

function getAllowedEmailDomain(): string {
  return process.env.ALLOWED_EMAIL_DOMAIN || '@iiitnr.edu.in';
}

function isUnverifiedEmailAllowed(): boolean {
  return process.env.ALLOW_UNVERIFIED_EMAIL === 'true';
}

function validateRegisterInput(
  body: RegisterBody,
): ValidationError | { email: string; password: string; name?: string } {
  const email = body?.email?.trim().toLowerCase();
  const password = body?.password;
  const name = sanitizeString(body?.name);

  if (!email || !password) {
    return { code: 400, message: 'email and password are required' };
  }

  if (password.length < 8) {
    return { code: 400, message: 'password must be at least 8 characters' };
  }

  return { email, password, name };
}

function validateLoginInput(
  body: LoginBody,
): ValidationError | { email: string; password: string } {
  const email = body?.email?.trim().toLowerCase();
  const password = body?.password;

  if (!email || !password) {
    return { code: 400, message: 'email and password are required' };
  }

  return { email, password };
}

function validateEmailDomain(email: string): ValidationError | null {
  const allowedDomain = getAllowedEmailDomain();

  if (!email.toLowerCase().endsWith(allowedDomain.toLowerCase())) {
    return {
      code: 403,
      message: `Only ${allowedDomain} email addresses are allowed. Your email (${email}) does not match the required domain.`,
    };
  }

  return null;
}

function validateEmailVerification(emailVerified?: boolean): ValidationError | null {
  if (!emailVerified && !isUnverifiedEmailAllowed()) {
    return {
      code: 400,
      message:
        'Google account email is not verified. Please verify your email address in your Google account settings.',
    };
  }

  return null;
}

function isValidationError(value: unknown): value is ValidationError {
  return typeof value === 'object' && value !== null && 'code' in value && 'message' in value;
}

async function verifyGoogleToken(
  idToken: string,
  allowedClientIds: string[],
): Promise<GooglePayload & { name?: string; imageUrl?: string }> {
  const client = new OAuth2Client(allowedClientIds[0]);
  const ticket = await client.verifyIdToken({
    idToken,
    audience: allowedClientIds,
  });

  const payload = ticket.getPayload();
  if (!payload) {
    throw new Error('Google token verification returned null payload');
  }

  return {
    ...payload,
    name: payload.name || payload.given_name,
    imageUrl: typeof payload.picture === 'string' ? payload.picture : undefined,
  };
}

function parseGoogleAuthError(err: unknown): ValidationError {
  const isAudienceMismatch =
    err instanceof Error &&
    (err.message.includes('audience') ||
      (err as Error & { code?: string }).code === 'auth/id-token-audience-mismatch');

  if (isAudienceMismatch) {
    return {
      code: 400,
      message: 'Google Sign-In failed: client ID mismatch. Contact the administrator.',
    };
  }

  return { code: 400, message: 'Google Sign-In failed. Please try again.' };
}

function findUserByEmail(email: string) {
  return db.query.user.findFirst({
    columns: { ...userPublicColumns, passwordHash: true, googleId: true },
    where: (u, { eq }) => eq(u.email, email),
  });
}

function findUserById(id: string) {
  return db.query.user.findFirst({
    columns: userPublicColumns,
    where: (u, { eq }) => eq(u.id, id),
  });
}

async function createUser(data: {
  email: string;
  name?: string;
  passwordHash?: string;
  googleId?: string;
  imageUrl?: string | null;
  role: UserRoleValue;
  batch?: string | null;
  branch?: string | null;
}) {
  const now = new Date().toISOString();

  const [created] = await db
    .insert(user)
    .values({
      id: crypto.randomUUID(),
      ...data,
      batch: data.batch ?? null,
      branch: data.branch ?? null,
      createdAt: now,
      updatedAt: now,
    })
    .returning({
      id: user.id,
      email: user.email,
      name: user.name,
      imageUrl: user.imageUrl,
      role: user.role,
      batch: user.batch,
      branch: user.branch,
      createdAt: user.createdAt,
    });

  return created;
}

function buildGoogleUserCreatePayload(
  email: string,
  name: string | null,
  googleId: string,
  imageUrl: string | null,
  requestedRole?: UserRoleValue,
) {
  const derived = deriveIiitnrProfileFromEmail(email);
  return {
    email,
    name: name ?? undefined,
    googleId,
    imageUrl,
    role: resolveSelfAssignedRole(requestedRole) ?? derived?.role ?? UserRole.PENDING,
    batch: derived?.batch ?? null,
    branch: derived?.branch ?? null,
  };
}

export async function findOrCreateGoogleUser(
  googleId: string,
  email: string,
  name: string | null,
  imageUrl: string | null,
  requestedRole?: UserRoleValue,
): Promise<UserResponse> {
  const normalizedEmail = email.trim().toLowerCase();
  const existingUser = await db.query.user.findFirst({
    columns: { ...userPublicColumns, googleId: true, passwordHash: true },
    where: (u, { eq, or }) => or(eq(u.googleId, googleId), eq(u.email, normalizedEmail)),
  });

  if (!existingUser) {
    const created = await createUser(
      buildGoogleUserCreatePayload(normalizedEmail, name, googleId, imageUrl, requestedRole),
    );

    if (!created) throw new Error('User creation failed');
    return created;
  }

  const derived = deriveIiitnrProfileFromEmail(normalizedEmail);
  const profileUpdates = derived
    ? pickDerivedProfileUpdates(derived, {
        batch: existingUser.batch,
        branch: existingUser.branch,
      })
    : {};

  // Linking a Google identity to an account that still carries a password would
  // let whoever set that password keep signing in as the Google user, so the
  // stored password hash is dropped as part of the link.
  const mustClearPassword = !existingUser.googleId && existingUser.passwordHash != null;

  const needsProfileUpdate = Object.keys(profileUpdates).length > 0;
  const needsGoogleMetadataUpdate =
    !existingUser.googleId ||
    (name != null && existingUser.name !== name) ||
    (imageUrl != null && existingUser.imageUrl !== imageUrl);

  if (!needsProfileUpdate && !needsGoogleMetadataUpdate && !mustClearPassword) {
    return toUserResponse(existingUser);
  }

  const [updated] = await db
    .update(user)
    .set({
      ...(needsGoogleMetadataUpdate
        ? {
            googleId: existingUser.googleId || googleId,
            name: name ?? existingUser.name,
            imageUrl: imageUrl ?? existingUser.imageUrl,
          }
        : {}),
      ...(mustClearPassword ? { passwordHash: null } : {}),
      ...profileUpdates,
      updatedAt: new Date().toISOString(),
    })
    .where(eq(user.id, existingUser.id))
    .returning({
      id: user.id,
      email: user.email,
      name: user.name,
      imageUrl: user.imageUrl,
      role: user.role,
      batch: user.batch,
      branch: user.branch,
    });

  if (!updated) throw new Error('User update failed');
  return updated;
}

function toUserResponse(userData: UserResponse): UserResponse {
  return {
    id: userData.id,
    email: userData.email,
    name: userData.name,
    imageUrl: userData.imageUrl,
    role: userData.role,
    batch: userData.batch ?? null,
    branch: userData.branch ?? null,
  };
}

async function handleRegister(
  app: { jwt: { sign: (payload: object) => string }; log: { error: (err: unknown) => void } },
  req: FastifyRequest,
  reply: FastifyReply,
) {
  const validation = validateRegisterInput(req.body as RegisterBody);

  if (isValidationError(validation)) {
    return reply.code(validation.code).send({ error: validation.message });
  }

  const { email, password, name } = validation;

  const existing = await findUserByEmail(email);
  if (existing) {
    return reply.code(400).send({ error: 'email already in use' });
  }

  try {
    const passwordHash = await hash(password, 12);
    const created = await createUser({ email, name, passwordHash, role: UserRole.PENDING });

    if (!created) {
      return reply.code(500).send({ error: 'failed to create user' });
    }

    const token = app.jwt.sign({ sub: created.id, role: created.role });
    return reply.code(201).send({ user: created, token });
  } catch (err) {
    app.log.error(err);
    return reply.code(400).send({ error: 'email already in use' });
  }
}

async function handleLogin(
  app: { jwt: { sign: (payload: object) => string } },
  req: FastifyRequest,
  reply: FastifyReply,
) {
  const validation = validateLoginInput(req.body as LoginBody);

  if (isValidationError(validation)) {
    return reply.code(validation.code).send({ error: validation.message });
  }

  const { email, password } = validation;
  const throttleKey = loginThrottleKey(email, req.ip);
  const blockedMs = getLoginBlockRemainingMs(throttleKey);
  if (blockedMs > 0) {
    return reply.code(429).send({
      error: `too many failed login attempts. Try again in ${Math.ceil(blockedMs / 60_000)} minute(s).`,
    });
  }

  const found = await findUserByEmail(email);

  if (!found) {
    recordLoginFailure(throttleKey);
    return reply.code(401).send({ error: 'invalid credentials' });
  }

  if (!found.passwordHash || found.googleId) {
    return reply.code(401).send({ error: 'this account uses Google Sign-In' });
  }

  const matches = await compare(password, found.passwordHash);
  if (!matches) {
    recordLoginFailure(throttleKey);
    return reply.code(401).send({ error: 'invalid credentials' });
  }

  clearLoginFailures(throttleKey);

  if (found.role === UserRole.PENDING) {
    return reply.code(403).send({ error: 'account pending approval by admin' });
  }

  const token = app.jwt.sign({ sub: found.id, role: found.role });

  await logAudit(
    { userId: found.id, action: AuditActionType.LOGIN, metadata: { method: 'password' } },
    req,
  );

  return reply.send({ user: toUserResponse(found), token });
}

async function handleGetMe(req: FastifyRequest, reply: FastifyReply) {
  const userId = (req.user as { sub?: string })?.sub;

  if (!userId) {
    return reply.code(401).send({ error: 'invalid token' });
  }

  const found = await findUserById(userId);

  if (!found) {
    return reply.code(401).send({ error: 'invalid token' });
  }

  return reply.send({ user: found });
}

async function handleGoogleAuth(
  app: {
    jwt: { sign: (payload: object) => string };
    log: { error: (data: object, msg: string) => void; warn: (msg: string) => void };
  },
  req: FastifyRequest,
  reply: FastifyReply,
) {
  const { idToken, role } = req.body as GoogleAuthBody;

  if (!idToken) {
    return reply.code(400).send({ error: 'idToken is required' });
  }

  const clientIds = getGoogleClientIds();
  if (!clientIds) {
    return reply.code(500).send({ error: 'Google OAuth not configured' });
  }

  try {
    const payload = await verifyGoogleToken(idToken, clientIds.all);

    if (!payload.email) {
      app.log.error({}, 'Google Sign-In: No email in token payload');
      return reply.code(400).send({ error: 'No email found in Google account' });
    }

    const domainError = validateEmailDomain(payload.email);
    if (domainError) {
      app.log.warn(`Google Sign-In: Email domain not allowed - ${payload.email}`);
      return reply.code(domainError.code).send({ error: domainError.message });
    }

    const verificationError = validateEmailVerification(payload.email_verified);
    if (verificationError) {
      return reply.code(verificationError.code).send({ error: verificationError.message });
    }

    const googleUser = await findOrCreateGoogleUser(
      payload.sub,
      payload.email,
      payload.name ?? null,
      payload.imageUrl ?? null,
      role as UserRoleValue,
    );

    const token = app.jwt.sign({ sub: googleUser.id, role: googleUser.role });

    await logAudit(
      { userId: googleUser.id, action: AuditActionType.LOGIN, metadata: { method: 'google' } },
      req,
    );

    return reply.send({ user: toUserResponse(googleUser), token });
  } catch (err) {
    app.log.error({ err }, 'Google Sign-In error');
    const error = parseGoogleAuthError(err);
    return reply.code(error.code).send({ error: error.message });
  }
}

const authRoutes: FastifyPluginCallback = (app, _opts, done) => {
  app.post(
    '/register',
    {
      schema: registerSchema,
      config: { rateLimit: { max: 15, timeWindow: '1 minute' } },
    },
    (req, reply) => handleRegister(app, req, reply),
  );

  app.post(
    '/login',
    {
      schema: loginSchema,
      config: { rateLimit: { max: 10, timeWindow: '1 minute' } },
    },
    (req, reply) => handleLogin(app, req, reply),
  );

  app.get(
    '/me',
    {
      preHandler: async (request) => {
        await request.jwtVerify();
      },
    },
    handleGetMe,
  );

  app.post(
    '/google',
    {
      schema: googleSchema,
      config: { rateLimit: { max: 100, timeWindow: '1 minute' } },
    },
    (req, reply) => handleGoogleAuth(app, req, reply),
  );

  done();
};

export default authRoutes;
