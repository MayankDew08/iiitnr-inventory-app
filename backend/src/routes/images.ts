import type { FastifyPluginCallback, FastifyRequest, FastifyReply } from 'fastify';
import { eq } from 'drizzle-orm';
import { db } from '../drizzle/db.js';
import { component } from '../drizzle/schema.js';
import { requireAdminOrLA } from '../middleware/auth.js';
import { AuditActionType } from '../utils/enums.js';
import { logAudit, getUserIdFromRequest } from '../utils/audit.js';
import { existsSync, mkdirSync, unlinkSync } from 'node:fs';
import { writeFile } from 'node:fs/promises';
import { join, resolve as pathResolve, sep } from 'node:path';

import { randomUUID } from 'node:crypto';

const UPLOADS_DIR = join(process.cwd(), 'uploads', 'images');
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
  return MAGIC_SIGNATURES.find((s) => s.match(bytes))?.type ?? null;
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
  return `${protocol}://${host}/api/uploads/images/${filename}`;
}

function deleteFileIfLocal(imageUrl: string | null | undefined): void {
  if (!imageUrl?.includes('/uploads/images/')) return;
  const oldFilename = imageUrl.split('/').pop();
  if (oldFilename) {
    const safeTarget = normalizeUploadPath(UPLOADS_DIR_RESOLVED, oldFilename);
    if (!safeTarget) return;
    try {
      unlinkSync(safeTarget);
    } catch {
      /* file may not exist */
    }
  }
}

async function handleUploadComponentImage(
  app: { log: { error: (err: unknown) => void } },
  req: FastifyRequest,
  reply: FastifyReply,
) {
  const { id } = req.params as { id?: string };
  if (!id) {
    return reply.code(400).send({ error: 'component id is required' });
  }

  ensureUploadsDir();

  try {
    const existing = await db.query.component.findFirst({
      where: (c, { eq }) => eq(c.id, id),
    });
    if (!existing) {
      return reply.code(404).send({ error: 'component not found' });
    }

    const data = await req.file({ limits: { fileSize: MAX_FILE_SIZE } });
    if (!data) {
      return reply.code(400).send({ error: 'no file uploaded' });
    }

    if (!ALLOWED_MIME_TYPES.includes(data.mimetype)) {
      data.file.resume();
      return reply.code(400).send({
        error: `invalid file type. Allowed: ${ALLOWED_MIME_TYPES.join(', ')}`,
      });
    }

    const chunks: Buffer[] = [];
    for await (const chunk of data.file) {
      chunks.push(chunk);
    }
    const bytes = Buffer.concat(chunks);

    if (data.file.truncated) {
      return reply.code(413).send({ error: 'file too large. Max 10MB' });
    }

    const detectedType = detectImageType(bytes);
    if (detectedType === null) {
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
      app.log.error(err);
      return reply.code(400).send({ error: 'invalid or corrupted image data' });
    }
    const filename = `${randomUUID()}.webp`;

    const safeTarget = normalizeUploadPath(UPLOADS_DIR_RESOLVED, filename);
    if (!safeTarget) {
      return reply.code(400).send({ error: 'invalid upload path' });
    }
    await writeFile(safeTarget, processedBuffer);

    deleteFileIfLocal(existing.imageUrl);

    const publicUrl = getPublicUrl(req, filename);

    const [updated] = await db
      .update(component)
      .set({ imageUrl: publicUrl, updatedAt: new Date().toISOString() })
      .where(eq(component.id, id))
      .returning();

    if (!updated) {
      return reply.code(500).send({ error: 'failed to update component image' });
    }

    await logAudit(
      {
        userId: getUserIdFromRequest(req),
        action: AuditActionType.UPDATE,
        entityType: 'Component',
        entityId: updated.id,
        oldValues: { imageUrl: existing.imageUrl },
        newValues: { imageUrl: publicUrl },
      },
      req,
    );

    return reply.send({ imageUrl: publicUrl, component: updated });
  } catch (err) {
    app.log.error(err);
    return reply.code(500).send({ error: 'failed to upload image' });
  }
}

async function handleDeleteComponentImage(
  app: { log: { error: (err: unknown) => void } },
  req: FastifyRequest,
  reply: FastifyReply,
) {
  const { id } = req.params as { id?: string };
  if (!id) {
    return reply.code(400).send({ error: 'component id is required' });
  }

  try {
    const existing = await db.query.component.findFirst({
      where: (c, { eq }) => eq(c.id, id),
    });
    if (!existing) {
      return reply.code(404).send({ error: 'component not found' });
    }

    if (!existing.imageUrl) {
      return reply.code(400).send({ error: 'component has no image' });
    }

    deleteFileIfLocal(existing.imageUrl);

    const [updated] = await db
      .update(component)
      .set({ imageUrl: null, updatedAt: new Date().toISOString() })
      .where(eq(component.id, id))
      .returning();

    if (!updated) {
      return reply.code(500).send({ error: 'failed to remove image' });
    }

    await logAudit(
      {
        userId: getUserIdFromRequest(req),
        action: AuditActionType.UPDATE,
        entityType: 'Component',
        entityId: id,
        oldValues: { imageUrl: existing.imageUrl },
        newValues: { imageUrl: null },
      },
      req,
    );

    return reply.send({ component: updated });
  } catch (err) {
    app.log.error(err);
    return reply.code(500).send({ error: 'failed to delete image' });
  }
}

const imagesRoutes: FastifyPluginCallback = (app, _opts, done) => {
  app.post('/:id/image', { preHandler: requireAdminOrLA }, (req, reply) =>
    handleUploadComponentImage(app, req, reply),
  );

  app.delete('/:id/image', { preHandler: requireAdminOrLA }, (req, reply) =>
    handleDeleteComponentImage(app, req, reply),
  );

  done();
};

export default imagesRoutes;
