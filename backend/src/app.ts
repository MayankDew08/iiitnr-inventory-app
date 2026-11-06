import 'dotenv/config';
import fs from 'node:fs';
import { join } from 'node:path';
import Fastify, { LogController } from 'fastify';
import type { FastifyInstance, FastifyRequest, FastifyReply } from 'fastify';
import cors from '@fastify/cors';
import helmet from '@fastify/helmet';
import rateLimit from '@fastify/rate-limit';
import jwt from '@fastify/jwt';
import multipart from '@fastify/multipart';
import staticFiles from '@fastify/static';
import { FastifySSEPlugin } from 'fastify-sse-v2';
import { pool } from './drizzle/db.js';
import routes from './routes/index.js';
import { startRequestExpirySweep } from './services/request-expiry.js';
import { startRenewalReminderSweep } from './services/renewal-reminder.js';
import { startRenewalChaseSweep } from './services/renewal-chase.js';
import { startLateFeeSweep } from './services/late-fee.js';
import { sendExpiryNoticesForRequests } from './services/expiry-notice.js';
import { isMailEnabled } from './services/MailService.js';
import { notifyRequestsExpired } from './services/NotificationService.js';
import { notifyRequestsUpdated } from './utils/events.js';
import { DomainError } from './utils/errors.js';

interface AppEnvironment {
  isTest: boolean;
  isProd: boolean;
}

interface ErrorWithStatus {
  statusCode?: number;
  code?: string;
  message?: string;
}

function getAppEnvironment(): AppEnvironment {
  const isTest =
    process.env.NODE_ENV === 'test' ||
    process.argv.some((arg) => arg.includes('--test') || arg.includes('test'));
  const isProd = process.env.NODE_ENV === 'production';

  return { isTest, isProd };
}

function getJwtSecret(): string {
  const jwtSecret = process.env.JWT_SECRET;

  if (!jwtSecret || jwtSecret === 'change-me') {
    throw new Error('JWT_SECRET is required and must not be "change-me"');
  }

  return jwtSecret;
}

function getAllowedOrigins(): string[] | undefined {
  const rawAllowedOrigins = process.env.ALLOWED_ORIGINS;

  if (!rawAllowedOrigins) return undefined;

  return rawAllowedOrigins
    .split(',')
    .map((origin) => origin.trim())
    .map((origin) => {
      try {
        return new URL(origin).origin;
      } catch {
        return origin.replace(/\/$/, '');
      }
    })
    .filter(Boolean);
}

function buildLoggerConfig(env: AppEnvironment) {
  if (env.isProd) return true;
  if (env.isTest) return false;

  return {
    transport: {
      target: 'pino-pretty',
      options: {
        colorize: true,
        colorizeObjects: true,
        translateTime: 'SYS:standard',
        ignore: 'pid,hostname',
      },
    },
  };
}
const SLOW_REQUEST_THRESHOLD_MS = 500;

function isHealthCheck(url: string): boolean {
  const path = url.split('?')[0] ?? url;
  return path === '/version' || path === '/health' || path.startsWith('/health/');
}

function buildCorsOrigin(env: AppEnvironment, allowedOrigins: string[] | undefined) {
  if (!env.isProd && !allowedOrigins) return true;
  return allowedOrigins ?? false;
}

function buildRateLimitConfig(app: FastifyInstance) {
  return {
    global: true,
    max: 100,
    timeWindow: '1 minute',
    // Bucket on the verified token subject, never on the raw Authorization
    // header: an unauthenticated client can send any header value it likes, so
    // keying on the header hands every request a fresh bucket. Unauthenticated
    // and forged-token requests fall back to the client IP.
    keyGenerator: async (request: FastifyRequest) => {
      const token = readBearerToken(request);
      if (token) {
        try {
          const payload = app.jwt.verify<{ sub?: string }>(token);
          if (payload.sub) return `user:${payload.sub}`;
        } catch {
          /* invalid or expired token: fall back to the IP bucket */
        }
      }
      return `ip:${request.ip}`;
    },
    // The plugin throws whatever this returns, so it has to be an Error carrying
    // the status code; a bare object reaches the error handler with no status
    // and is reported as a 500.
    errorResponseBuilder: (_request: FastifyRequest, context: { ttl: number; statusCode: number }) => {
      const error = new Error(
        `Too many requests. Please wait ${Math.ceil(context.ttl / 1000)} seconds before retrying.`,
      ) as Error & { statusCode: number; code: string };
      error.statusCode = context.statusCode;
      error.code = 'RATE_LIMITED';
      return error;
    },
  };
}

function readBearerToken(request: FastifyRequest): string | undefined {
  const header = request.headers.authorization;
  if (!header?.startsWith('Bearer ')) return undefined;
  const token = header.slice('Bearer '.length).trim();
  return token.length > 0 ? token : undefined;
}

async function parseFormUrlEncoded(req: FastifyRequest, body: string) {
  if (req.method === 'DELETE' || !body || body.length === 0) {
    return {};
  }

  const querystring = await import('node:querystring');
  return querystring.parse(body);
}

function formatSchemaError(
  errors: { params?: { missingProperty?: string }; instancePath?: string; message?: string }[],
) {
  const first = errors[0];

  if (first?.params && 'missingProperty' in first.params) {
    const field = first.params.missingProperty as string;
    return new Error(`${field} is required`);
  }

  if (first?.instancePath) {
    const field = first.instancePath.replace(/^\//, '');
    return new Error(`${field} ${first.message ?? 'is invalid'}`);
  }

  return new Error(first?.message ?? 'Validation error');
}

function isLoopbackSocket(request: FastifyRequest): boolean {
  const addr = request.socket.remoteAddress;
  return addr === '127.0.0.1' || addr === '::1' || addr === '::ffff:127.0.0.1';
}

// Behind Caddy (or any proxy on a private network) the connection address is the
// proxy, so X-Forwarded-For has to be trusted for rate limiting and audit logs
// to see the real client. Trusting only loopback/private peers keeps a direct
// public connection from spoofing its address.
function isProxyAddress(address: string): boolean {
  const normalized = address.startsWith('::ffff:') ? address.slice('::ffff:'.length) : address;

  if (normalized.includes(':')) {
    const lower = normalized.toLowerCase();
    return (
      lower === '::1' ||
      lower === '::' ||
      lower.startsWith('fc') || // IPv6 unique-local
      lower.startsWith('fd') ||
      lower.startsWith('fe8') || // IPv6 link-local
      lower.startsWith('fe9') ||
      lower.startsWith('fea') ||
      lower.startsWith('feb')
    );
  }

  const octets = normalized.split('.').map((part) => Number.parseInt(part, 10));
  if (octets.length !== 4 || octets.some((octet) => Number.isNaN(octet))) return false;

  const [first, second] = octets as [number, number, number, number];
  return (
    first === 10 ||
    first === 127 ||
    (first === 172 && second >= 16 && second <= 31) ||
    (first === 192 && second === 168) ||
    (first === 169 && second === 254)
  );
}

async function handleHttpsRedirect(request: FastifyRequest, reply: FastifyReply) {
  if (isLoopbackSocket(request)) {
    return;
  }
  if (request.headers['x-forwarded-proto'] === 'http') {
    const host = request.headers.host;
    await reply.redirect(`https://${host ?? ''}${request.url}`);
  }
}

function handleError(error: Error, request: FastifyRequest, reply: FastifyReply) {
  if (reply.raw.headersSent || reply.sent) {
    request.log.error({ err: error }, 'Unhandled error after headers sent');
    return;
  }

  let statusCode = 500;
  let message = 'Internal Server Error';
  let code: string | undefined;

  const isDomainError =
    error instanceof DomainError ||
    (typeof error === 'object' &&
      error !== null &&
      (error as { isDomainError?: boolean }).isDomainError === true);

  if (isDomainError) {
    const err = error as DomainError;
    statusCode = err.statusCode;
    message = err.message;
    code = err.code;
  } else {
    const err = error as ErrorWithStatus;
    if (isValidHttpErrorCode(err.statusCode)) {
      statusCode = err.statusCode;
      message = err.message ?? 'Request failed';
      code = err.code;
    }
  }

  if (statusCode >= 500) {
    request.log.error(error);
  } else {
    request.log.warn({ code, statusCode }, message);
  }

  reply.code(statusCode).send({ error: message, code });
}

function isValidHttpErrorCode(code: number | undefined): code is number {
  return code !== undefined && code >= 400 && code < 600;
}

function handleRootRoute() {
  return { message: 'IIITNR Inventory App Backend' };
}

const APP_VERSION: string = (() => {
  try {
    const pkg = JSON.parse(fs.readFileSync('./package.json', 'utf-8'));
    return typeof pkg.version === 'string' ? pkg.version : 'unknown';
  } catch {
    return 'unknown';
  }
})();

let appReady = false;

export function markAppReady() {
  appReady = true;
}

function handleHealthCheck(reply: FastifyReply) {
  return reply.code(200).send({ status: 'ok' });
}

async function handleReadyCheck(app: FastifyInstance, reply: FastifyReply) {
  if (!appReady) {
    return reply.code(503).send({ status: 'starting' });
  }

  try {
    await pool.query('SELECT 1');
    return reply.code(200).send({ status: 'ready', db: 'up' });
  } catch (error) {
    app.log.error({ err: error }, 'Readiness check DB query failed');
    return reply.code(503).send({ status: 'not_ready', db: 'down' });
  }
}

async function registerPlugins(app: FastifyInstance, env: AppEnvironment) {
  const allowedOrigins = getAllowedOrigins();

  await app.register(cors, {
    origin: buildCorsOrigin(env, allowedOrigins),
    methods: ['GET', 'POST', 'PUT', 'DELETE', 'OPTIONS'],
    allowedHeaders: ['Content-Type', 'Authorization'],
    exposedHeaders: ['Last-Modified'],
  });

  await app.register(helmet, {
    crossOriginResourcePolicy: { policy: 'cross-origin' },
  });

  if (!env.isTest) {
    await app.register(rateLimit, buildRateLimitConfig(app));
  }

  await app.register(jwt, {
    secret: getJwtSecret(),
    sign: { expiresIn: '7d' },
  });

  await app.register(multipart, {
    limits: { fileSize: 10 * 1024 * 1024 },
  });

  await app.register(FastifySSEPlugin);

  await app.register(staticFiles, {
    root: join(process.cwd(), 'uploads'),
    prefix: '/uploads/',
  });

  await app.register(staticFiles, {
    root: join(process.cwd(), 'uploads'),
    prefix: '/api/uploads/',
    decorateReply: false,
  });
}

function setupContentParsers(app: FastifyInstance) {
  app.addContentTypeParser(
    'application/x-www-form-urlencoded',
    { parseAs: 'string' },
    parseFormUrlEncoded,
  );

  app.addContentTypeParser('text/csv', { parseAs: 'string' }, (_req, body, done) =>
    done(null, body),
  );
}

function setupErrorHandling(app: FastifyInstance) {
  app.setSchemaErrorFormatter(formatSchemaError);
  app.setErrorHandler(handleError);
}

function setupHooks(app: FastifyInstance, env: AppEnvironment) {
  if (env.isProd) {
    app.addHook('onRequest', handleHttpsRedirect);
  }

  let stopRequestExpirySweep: (() => void) | undefined;
  let stopRenewalReminderSweep: (() => void) | undefined;
  let stopRenewalChaseSweep: (() => void) | undefined;
  let stopLateFeeSweep: (() => void) | undefined;
  if (!env.isTest) {
    app.addHook('onReady', (done) => {
      if (isMailEnabled()) {
        app.log.info('Outgoing mail is enabled (MAIL_ENABLED=true)');
      } else {
        app.log.info('Outgoing mail is disabled');
      }

      stopRequestExpirySweep = startRequestExpirySweep(
        (err) => {
          app.log.error({ err }, 'Request expiry sweep failed');
          notifyRequestsUpdated();
        },
        (expired) => {
          notifyRequestsExpired(expired)
            .catch((err) => app.log.error({ err }, 'Failed to notify expired requests'))
            .finally(() => notifyRequestsUpdated());

          sendExpiryNoticesForRequests(expired.map((request) => request.id)).catch((err) =>
            app.log.error({ err }, 'Failed to send expiry notices'),
          );
        },
      );
      stopRenewalReminderSweep = startRenewalReminderSweep((err) =>
        app.log.error({ err }, 'Renewal reminder sweep failed'),
      );
      stopRenewalChaseSweep = startRenewalChaseSweep((err) =>
        app.log.error({ err }, 'Renewal chase sweep failed'),
      );
      stopLateFeeSweep = startLateFeeSweep((err) =>
        app.log.error({ err }, 'Late fee sweep failed'),
      );
      done();
    });
  }

  app.addHook('onClose', async () => {
    stopRequestExpirySweep?.();
    stopRenewalReminderSweep?.();
    stopRenewalChaseSweep?.();
    stopLateFeeSweep?.();
    if (!env.isTest) {
      await pool.end();
    }
  });

  app.addHook('onResponse', (request, reply) => {
    const { url, method } = request;
    const statusCode = reply.statusCode;
    const responseTime = reply.elapsedTime;

    if (statusCode < 400 && responseTime < SLOW_REQUEST_THRESHOLD_MS) return;
    if (statusCode < 400 && isHealthCheck(url)) return;

    const detail = { method, url, statusCode, responseTime: Math.round(responseTime) };
    const message = `${method} ${url} ${statusCode} (${Math.round(responseTime)}ms)`;

    if (statusCode >= 500) {
      request.log.error(detail, message);
    } else if (statusCode >= 400) {
      request.log.warn(detail, message);
    } else {
      request.log.warn(detail, `Slow request: ${message}`);
    }
  });
}

const GITHUB_DOWNLOAD_BASE =
  'https://github.com/sharifmdathar/iiitnr-inventory-app/releases/latest/download';

function setupRoutes(app: FastifyInstance) {
  app.get('/', handleRootRoute);
  app.get('/health', (_, reply) => handleHealthCheck(reply));
  app.get('/ready', { logLevel: 'silent' }, (_, reply) => handleReadyCheck(app, reply));
  app.get('/version', (_, reply) => {
    reply.send({ version: APP_VERSION });
  });

  app.get('/download', async (request, reply) => {
    const userAgent = request.headers['user-agent'] || '';

    if (userAgent.includes('Android')) {
      return reply.redirect(`${GITHUB_DOWNLOAD_BASE}/com.iiitnr.inventoryapp-${APP_VERSION}.apk`);
    }
    if (userAgent.includes('Windows')) {
      return reply.redirect(`${GITHUB_DOWNLOAD_BASE}/IIITNR-Inventory-App.exe`);
    }
    if (userAgent.includes('Linux')) {
      return reply.redirect(`${GITHUB_DOWNLOAD_BASE}/IIITNR-Inventory-App.AppImage`);
    }

    await reply.redirect('https://github.com/sharifmdathar/iiitnr-inventory-app/releases/latest');
  });

  app.get('/download/android', async (_, reply) => {
    await reply.redirect(`${GITHUB_DOWNLOAD_BASE}/com.iiitnr.inventoryapp-${APP_VERSION}.apk`);
  });

  app.get('/download/windows', async (_, reply) => {
    await reply.redirect(`${GITHUB_DOWNLOAD_BASE}/IIITNR-Inventory-App.exe`);
  });

  app.get('/download/linux', async (_, reply) => {
    await reply.redirect(`${GITHUB_DOWNLOAD_BASE}/IIITNR-Inventory-App.AppImage`);
  });
}

export async function buildApp() {
  const env = getAppEnvironment();

  const app = Fastify({
    bodyLimit: 11 * 1024 * 1024,
    logController: new LogController({ disableRequestLogging: true }),
    logger: buildLoggerConfig(env),
    trustProxy: (address: string) => isProxyAddress(address),
  });

  await registerPlugins(app, env);
  setupContentParsers(app);
  setupErrorHandling(app);
  setupHooks(app, env);
  setupRoutes(app);
  await app.register(routes);

  return app;
}
