import type { FastifyPluginCallback, FastifyReply, FastifyRequest } from 'fastify';
import { existsSync, mkdirSync, unlinkSync } from 'node:fs';
import { writeFile } from 'node:fs/promises';
import { join, resolve as pathResolve, sep } from 'node:path';
import { randomUUID } from 'node:crypto';
import { requireAuth } from '../middleware/auth.js';
import {
  createPaymentIntent,
  getPaymentConfig,
  submitPayment,
} from '../services/PaymentService.js';
import { UnauthorizedError } from '../utils/errors.js';

const UPLOADS_DIR = join(process.cwd(), 'uploads', 'payments');
const UPLOADS_DIR_RESOLVED = pathResolve(UPLOADS_DIR);

const ALLOWED_MIME_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'image/gif', 'image/avif'];
const MAX_FILE_SIZE = 10 * 1024 * 1024;

const MAGIC_SIGNATURES: ReadonlyArray<{ type: string; match: (bytes: Buffer) => boolean }> = [
  {
    type: 'image/jpeg',
    match: (b) => b.length >= 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff,
  },
  {
    type: 'image/png',
    match: (b) =>
      b.length >= 8 &&
      b.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])),
  },
  {
    type: 'image/webp',
    match: (b) =>
      b.length >= 12 &&
      b.subarray(0, 4).toString('latin1') === 'RIFF' &&
      b.subarray(8, 12).toString('latin1') === 'WEBP',
  },
  {
    type: 'image/gif',
    match: (b) =>
      b.length >= 6 &&
      (b.subarray(0, 6).toString('latin1') === 'GIF87a' ||
        b.subarray(0, 6).toString('latin1') === 'GIF89a'),
  },
  {
    type: 'image/avif',
    match: (b) =>
      b.length >= 12 &&
      b.subarray(4, 8).toString('latin1') === 'ftyp' &&
      (b.subarray(8, 12).toString('latin1') === 'avif' ||
        b.subarray(8, 12).toString('latin1') === 'avis'),
  },
];

function detectImageType(bytes: Buffer): string | null {
  return MAGIC_SIGNATURES.find((signature) => signature.match(bytes))?.type ?? null;
}

function ensureUploadsDir() {
  if (!existsSync(UPLOADS_DIR)) {
    mkdirSync(UPLOADS_DIR, { recursive: true });
  }
}

function normalizeUploadPath(base: string, rawName: string): string | null {
  const target = pathResolve(base, rawName);
  if (!target.startsWith(base + sep)) return null;
  return target;
}

function getPublicUrl(req: FastifyRequest, filename: string): string {
  const host = req.headers.host ?? 'localhost:4000';
  const protocol = req.headers['x-forwarded-proto'] === 'https' ? 'https' : req.protocol;
  return `${protocol}://${host}/api/uploads/payments/${filename}`;
}

function deleteUploadedFile(target: string): void {
  try {
    unlinkSync(target);
  } catch {
    /* file may not exist */
  }
}

function getCurrentUserId(req: FastifyRequest): string {
  const sub = (req.user as { sub?: string } | undefined)?.sub;
  if (!sub) {
    throw new UnauthorizedError();
  }
  return sub;
}

async function handleSubmit(req: FastifyRequest, reply: FastifyReply) {
  const { id } = req.params as { id?: string };
  if (!id) {
    return reply.code(400).send({ error: 'payment id is required' });
  }

  const userId = getCurrentUserId(req);

  let utr: string | null = null;
  let bytes: Buffer | null = null;
  let uploadError: { statusCode: number; message: string } | null = null;

  try {
    for await (const part of req.parts({ limits: { fileSize: MAX_FILE_SIZE } })) {
      if (part.type === 'field') {
        if (part.fieldname === 'utr') {
          utr = String(part.value);
        }
        continue;
      }

      if (part.fieldname !== 'proof' || bytes !== null) {
        part.file.resume();
        continue;
      }

      if (!ALLOWED_MIME_TYPES.includes(part.mimetype)) {
        part.file.resume();
        uploadError = {
          statusCode: 400,
          message: `invalid file type. Allowed: ${ALLOWED_MIME_TYPES.join(', ')}`,
        };
        continue;
      }

      const chunks: Buffer[] = [];
      for await (const chunk of part.file) {
        chunks.push(chunk as Buffer);
      }

      if (part.file.truncated) {
        uploadError = { statusCode: 413, message: 'file too large. Max 10MB' };
        continue;
      }

      bytes = Buffer.concat(chunks);
    }
  } catch (err) {
    req.log.warn({ err }, 'failed to parse payment submit multipart body');
    return reply.code(400).send({ error: 'invalid multipart body' });
  }

  const trimmedUtr = utr?.trim() ?? '';
  if (!trimmedUtr) {
    return reply.code(400).send({ error: 'utr_required' });
  }

  if (uploadError) {
    return reply.code(uploadError.statusCode).send({ error: uploadError.message });
  }

  if (bytes === null) {
    return reply.code(400).send({ error: 'proof_required' });
  }

  if (detectImageType(bytes) === null) {
    return reply.code(400).send({
      error: `invalid file content. Allowed: ${ALLOWED_MIME_TYPES.join(', ')}`,
    });
  }

  let processedBuffer: Buffer;
  try {
    processedBuffer = await new Bun.Image(bytes)
      .resize(1024, 1024, {
        fit: 'inside',
        withoutEnlargement: true,
      })
      .webp({ quality: 80 })
      .buffer();
  } catch (err) {
    req.log.error(err);
    return reply.code(400).send({ error: 'invalid or corrupted image data' });
  }

  ensureUploadsDir();
  const filename = `${randomUUID()}.webp`;
  const safeTarget = normalizeUploadPath(UPLOADS_DIR_RESOLVED, filename);
  if (!safeTarget) {
    return reply.code(400).send({ error: 'invalid upload path' });
  }

  await writeFile(safeTarget, processedBuffer);

  try {
    const updated = await submitPayment(id, userId, trimmedUtr, getPublicUrl(req, filename));
    return reply.send({ payment: updated });
  } catch (err) {
    // The payment was not written, so do not leave an orphan proof behind.
    deleteUploadedFile(safeTarget);
    throw err;
  }
}

const paymentsRoutes: FastifyPluginCallback = (app, _opts, done) => {
  app.post('/', { preHandler: requireAuth }, async (req, reply) => {
    const created = await createPaymentIntent(getCurrentUserId(req));
    const { upiVpa, payeeName } = getPaymentConfig();

    return reply.send({
      payment: created,
      upi: {
        upiVpa,
        payeeName,
        reference: created.id,
      },
    });
  });

  app.get('/config', { preHandler: requireAuth }, (_req, reply) =>
    reply.send(getPaymentConfig()),
  );

  app.post('/:id/submit', { preHandler: requireAuth }, (req, reply) => handleSubmit(req, reply));

  done();
};

export default paymentsRoutes;
