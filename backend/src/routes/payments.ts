import type { FastifyPluginCallback, FastifyReply, FastifyRequest } from 'fastify';
import { existsSync, mkdirSync, unlinkSync } from 'node:fs';
import { readFile, writeFile } from 'node:fs/promises';
import { join, resolve as pathResolve, sep } from 'node:path';
import { randomUUID } from 'node:crypto';
import { isAdminOrLA, requireAdminOrLA, requireAuth } from '../middleware/auth.js';
import {
  createPaymentIntent,
  getPaymentById,
  getPaymentConfig,
  getPaymentForAdmin,
  listMyPayments,
  listPendingForAdmin,
  rejectPayment,
  submitPayment,
  toPaymentDto,
  verifyPayment,
} from '../services/PaymentService.js';
import { paymentRejectionReasonValues } from '../utils/enums.js';
import type { PaymentRejectionReasonValue } from '../utils/enums.js';
import { UnauthorizedError } from '../utils/errors.js';

// Proofs live under private-uploads/ (not the publicly served uploads/) and
// are only ever returned by the authenticated GET /:id/proof route.
const PROOFS_DIR = join(process.cwd(), 'private-uploads', 'payments');
const PROOFS_DIR_RESOLVED = pathResolve(PROOFS_DIR);

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

function ensureProofsDir() {
  if (!existsSync(PROOFS_DIR)) {
    mkdirSync(PROOFS_DIR, { recursive: true });
  }
}

function normalizeUploadPath(base: string, rawName: string): string | null {
  const target = pathResolve(base, rawName);
  if (!target.startsWith(base + sep)) return null;
  return target;
}

/**
 * Stored as a bare file name only; the API maps it to `proofImageUrl`
 * (`/api/payments/{id}/proof`) via toPaymentDto, so the raw disk layout is
 * never part of the wire format.
 */
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

  // Cheap checks first: a UTR is exactly 12 digits once spaces are removed.
  // This runs before the image work so a malformed UTR never pays for a
  // resize/re-encode.
  const normalizedUtr = trimmedUtr.replace(/\s+/g, '');
  if (!/^\d{12}$/.test(normalizedUtr)) {
    return reply.code(400).send({ error: 'invalid_utr', code: 'INVALID_UTR' });
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

  ensureProofsDir();
  const filename = `${randomUUID()}.webp`;
  const safeTarget = normalizeUploadPath(PROOFS_DIR_RESOLVED, filename);
  if (!safeTarget) {
    return reply.code(400).send({ error: 'invalid upload path' });
  }

  await writeFile(safeTarget, processedBuffer);

  try {
    const updated = await submitPayment(id, userId, normalizedUtr, filename);
    return reply.send({ payment: toPaymentDto(updated) });
  } catch (err) {
    // The payment was not written, so do not leave an orphan proof behind.
    deleteUploadedFile(safeTarget);
    throw err;
  }
}

async function handleProof(req: FastifyRequest, reply: FastifyReply) {
  const { id } = req.params as { id?: string };
  if (!id) {
    return reply.code(404).send({ error: 'payment not found' });
  }
  const record = await getPaymentById(id);
  if (!record) {
    return reply.code(404).send({ error: 'payment not found' });
  }
  const sub = (req.user as { sub?: string } | undefined)?.sub;
  const role = (req.user as { role?: string } | undefined)?.role as
    import('../utils/enums.js').UserRoleValue | undefined;
  if (record.userId !== sub && !isAdminOrLA(role)) {
    return reply.code(403).send({ error: 'forbidden' });
  }
  if (!record.proofFileName) {
    return reply.code(404).send({ error: 'proof not found' });
  }
  const target = normalizeUploadPath(PROOFS_DIR_RESOLVED, record.proofFileName);
  if (!target) {
    return reply.code(404).send({ error: 'proof not found' });
  }
  try {
    const bytes = await readFile(target);
    return reply
      .code(200)
      .header('content-type', 'image/webp')
      .header('cache-control', 'private, no-store')
      .send(bytes);
  } catch {
    return reply.code(404).send({ error: 'proof not found' });
  }
}

const paymentsRoutes: FastifyPluginCallback = (app, _opts, done) => {
  // Static admin paths are registered before the /:id detail path.
  app.get('/admin/pending', { preHandler: requireAdminOrLA }, async (_req, reply) => {
    const payments = await listPendingForAdmin();
    return reply.send({ payments });
  });

  app.get('/admin/:id', { preHandler: requireAdminOrLA }, async (req, reply) => {
    const { id } = req.params as { id?: string };
    if (!id) {
      return reply.code(400).send({ error: 'payment id is required' });
    }
    const detail = await getPaymentForAdmin(id);
    return reply.send({ ...detail, payment: toPaymentDto(detail.payment) });
  });

  app.post('/admin/:id/verify', { preHandler: requireAdminOrLA }, async (req, reply) => {
    const { id } = req.params as { id?: string };
    if (!id) {
      return reply.code(400).send({ error: 'payment id is required' });
    }
    const updated = await verifyPayment(id, getCurrentUserId(req));
    return reply.send({ payment: toPaymentDto(updated) });
  });

  app.post('/admin/:id/reject', { preHandler: requireAdminOrLA }, async (req, reply) => {
    const { id } = req.params as { id?: string };
    if (!id) {
      return reply.code(400).send({ error: 'payment id is required' });
    }

    const body = (req.body ?? {}) as { reasonCode?: unknown; notes?: unknown };
    const { reasonCode } = body;
    if (
      typeof reasonCode !== 'string' ||
      !paymentRejectionReasonValues.includes(reasonCode as PaymentRejectionReasonValue)
    ) {
      return reply.code(400).send({ error: 'invalid reasonCode' });
    }

    let notes: string | undefined;
    if (body.notes !== undefined && body.notes !== null) {
      if (typeof body.notes !== 'string') {
        return reply.code(400).send({ error: 'notes must be a string' });
      }
      notes = body.notes.trim();
      if (notes.length > 500) {
        return reply.code(400).send({ error: 'notes must be at most 500 characters' });
      }
    }

    const updated = await rejectPayment(
      id,
      getCurrentUserId(req),
      reasonCode as PaymentRejectionReasonValue,
      notes,
    );
    return reply.send({ payment: toPaymentDto(updated) });
  });

  app.post('/', { preHandler: requireAuth }, async (req, reply) => {
    const created = await createPaymentIntent(getCurrentUserId(req));
    const { upiVpa, payeeName } = getPaymentConfig();

    return reply.send({
      payment: toPaymentDto(created),
      upi: {
        upiVpa,
        payeeName,
        // NPCI caps `tr` at 35 alphanumeric characters, so the UUID's hyphens
        // have to go: a 36-char value with dashes gets the intent accepted by
        // the PSP and then rejected at debit time, which surfaces to the payer
        // as a generic "UPI system is facing some technical issue".
        reference: created.id.replaceAll('-', ''),
      },
    });
  });

  app.get('/config', { preHandler: requireAuth }, (_req, reply) => reply.send(getPaymentConfig()));

  app.get('/mine', { preHandler: requireAuth }, async (req, reply) => {
    const payments = await listMyPayments(getCurrentUserId(req));
    return reply.send({ payments: payments.map(toPaymentDto) });
  });

  app.get('/:id/proof', { preHandler: requireAuth }, (req, reply) => handleProof(req, reply));

  app.post('/:id/submit', { preHandler: requireAuth }, (req, reply) => handleSubmit(req, reply));

  done();
};

export default paymentsRoutes;
