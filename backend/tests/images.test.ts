import './test-setup.js';

import { beforeAll, afterAll, describe, test } from 'bun:test';
import assert from 'node:assert/strict';
import { existsSync, mkdirSync, readdirSync, unlinkSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { db } from '../src/drizzle/db.js';
import { auditLog } from '../src/drizzle/schema.js';
import { buildApp } from '../src/app.js';
import { and, eq } from 'drizzle-orm';
import {
  createComponent,
  createUser,
  deleteComponents,
  deleteUsers,
  Location,
  ComponentCategory,
  UserRole,
} from './helpers.js';

let app: Awaited<ReturnType<typeof buildApp>>;
let adminToken: string;
let adminUserId: string;

beforeAll(async () => {
  app = await buildApp();

  const { hash } = await import('bcryptjs');
  const passwordHash = await hash('password123', 12);

  const adminUser = await createUser({
    email: `image_admin_${crypto.randomUUID()}@example.com`,
    passwordHash,
    name: 'Image Admin',
    role: UserRole.ADMIN,
  });
  adminUserId = adminUser.id;
  adminToken = app.jwt.sign({ sub: adminUser.id, role: adminUser.role }, { expiresIn: '1h' });
});

afterAll(async () => {
  cleanUploadsDir();
  await deleteUsers([adminUserId].filter(Boolean));
  await app.close();
});

const UPLOADS_IMAGES_DIR = join(process.cwd(), 'uploads', 'images');

describe('Served uploads', () => {
  test('are cached immutably for a year', async () => {
    mkdirSync(UPLOADS_IMAGES_DIR, { recursive: true });
    const filename = `${crypto.randomUUID()}.webp`;
    writeFileSync(join(UPLOADS_IMAGES_DIR, filename), 'cached-bytes');

    try {
      for (const url of [`/uploads/images/${filename}`, `/api/uploads/images/${filename}`]) {
        const response = await app.inject({ method: 'GET', url });

        assert.equal(response.statusCode, 200, `${url} should be served`);
        const cacheControl = response.headers['cache-control'];
        assert.ok(
          typeof cacheControl === 'string' && cacheControl.includes('immutable'),
          `${url} should be immutable, got ${cacheControl}`,
        );
        assert.ok(
          typeof cacheControl === 'string' && cacheControl.includes('max-age=31536000'),
          `${url} should be cached for a year, got ${cacheControl}`,
        );
      }
    } finally {
      unlinkSync(join(UPLOADS_IMAGES_DIR, filename));
    }
  });
});

function cleanUploadsDir() {
  if (!existsSync(UPLOADS_IMAGES_DIR)) return;
  for (const f of readdirSync(UPLOADS_IMAGES_DIR)) {
    if (f !== '.gitkeep') {
      try {
        unlinkSync(join(UPLOADS_IMAGES_DIR, f));
      } catch {
        /* ignore */
      }
    }
  }
}

const VALID_PNG = Buffer.from([
  0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
  0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1f, 0x15, 0xc4,
  0x89, 0x00, 0x00, 0x00, 0x0a, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9c, 0x63, 0x00, 0x01, 0x00, 0x00,
  0x05, 0x00, 0x01, 0x0d, 0x0a, 0x2d, 0xb4, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44, 0xae,
  0x42, 0x60, 0x82,
]);

function buildMultipartBody(params: {
  fieldName: string;
  filename: string;
  contentType: string;
  bytes: Uint8Array;
}) {
  const boundary = `----iiitnr-${crypto.randomUUID()}`;
  const prefix = Buffer.from(
    `--${boundary}\r\nContent-Disposition: form-data; name="${params.fieldName}"; filename="${params.filename}"\r\nContent-Type: ${params.contentType}\r\n\r\n`,
  );
  const suffix = Buffer.from(`\r\n--${boundary}--\r\n`);
  const body = Buffer.concat([prefix, Buffer.from(params.bytes), suffix]);

  return {
    body,
    contentType: `multipart/form-data; boundary=${boundary}`,
  };
}

async function getImageAuditLogs(componentId: string) {
  return db
    .select()
    .from(auditLog)
    .where(and(eq(auditLog.entityType, 'Component'), eq(auditLog.entityId, componentId)));
}

describe('Component image uploads', () => {
  test('uploads, audits, and deletes a component image', async () => {
    const component = await createComponent({
      name: 'Image Upload Component',
      totalQuantity: 1,
      availableQuantity: 1,
      category: ComponentCategory.Sensors,
      location: Location.IoT_Lab,
    });

    const upload = buildMultipartBody({
      fieldName: 'image',
      filename: 'component.png',
      contentType: 'image/png',
      bytes: VALID_PNG,
    });

    const uploadResponse = await app.inject({
      method: 'POST',
      url: `/components/${component.id}/image`,
      headers: {
        authorization: `Bearer ${adminToken}`,
        'content-type': upload.contentType,
      },
      payload: upload.body,
    });

    assert.equal(uploadResponse.statusCode, 200);
    const uploadBody = uploadResponse.json();
    assert.ok(uploadBody.imageUrl.includes('/uploads/images/'));
    assert.equal(uploadBody.component.imageUrl, uploadBody.imageUrl);
    assert.ok(uploadBody.imageUrl.endsWith('.webp'));

    let logs = await getImageAuditLogs(component.id);
    const uploadLog = logs.find((entry) => entry.action === 'UPDATE');
    assert.ok(uploadLog, 'upload should create an UPDATE audit log');
    if (!uploadLog) throw new Error('upload audit log missing');
    assert.ok(uploadLog.newValues);
    const uploadNewValues = JSON.parse(uploadLog.newValues as string);
    assert.equal(uploadNewValues.imageUrl, uploadBody.imageUrl);

    const deleteResponse = await app.inject({
      method: 'DELETE',
      url: `/components/${component.id}/image`,
      headers: {
        authorization: `Bearer ${adminToken}`,
      },
    });

    assert.equal(deleteResponse.statusCode, 200);

    logs = await getImageAuditLogs(component.id);
    const deleteLog = logs.filter((entry) => entry.action === 'UPDATE').at(-1);
    assert.ok(deleteLog, 'delete should create a second UPDATE audit log');
    if (!deleteLog) throw new Error('delete audit log missing');
    assert.ok(deleteLog.oldValues);
    assert.ok(deleteLog.newValues);
    const deleteOldValues = JSON.parse(deleteLog.oldValues as string);
    const deleteNewValues = JSON.parse(deleteLog.newValues as string);
    assert.equal(deleteOldValues.imageUrl, uploadBody.imageUrl);
    assert.equal(deleteNewValues.imageUrl, null);

    await deleteComponents([component.id]);
  });

  test('rejects invalid image formats', async () => {
    const component = await createComponent({
      name: 'Invalid Format Component',
      totalQuantity: 1,
      availableQuantity: 1,
    });

    const upload = buildMultipartBody({
      fieldName: 'image',
      filename: 'component.pdf',
      contentType: 'application/pdf',
      bytes: Buffer.from('%PDF-1.7\n'),
    });

    const response = await app.inject({
      method: 'POST',
      url: `/components/${component.id}/image`,
      headers: {
        authorization: `Bearer ${adminToken}`,
        'content-type': upload.contentType,
      },
      payload: upload.body,
    });

    assert.equal(response.statusCode, 400);
    assert.ok(response.json().error.includes('invalid file type'));

    await deleteComponents([component.id]);
  });

  test('rejects non-image bytes even when content-type claims image/jpeg', async () => {
    const component = await createComponent({
      name: 'Spoofed Content Type Component',
      totalQuantity: 1,
      availableQuantity: 1,
    });

    // Windows PE executable magic bytes masquerading as a JPEG
    const upload = buildMultipartBody({
      fieldName: 'image',
      filename: 'component.jpg',
      contentType: 'image/jpeg',
      bytes: Buffer.concat([
        Buffer.from([0x4d, 0x5a, 0x90, 0x00]),
        Buffer.from('This is definitely not an image', 'ascii'),
      ]),
    });

    const response = await app.inject({
      method: 'POST',
      url: `/components/${component.id}/image`,
      headers: {
        authorization: `Bearer ${adminToken}`,
        'content-type': upload.contentType,
      },
      payload: upload.body,
    });

    assert.equal(response.statusCode, 400);
    assert.ok(response.json().error.includes('invalid file content'));

    await deleteComponents([component.id]);
  });

  test('accepts real image bytes with a matching content-type', async () => {
    const component = await createComponent({
      name: 'Real Bytes Component',
      totalQuantity: 1,
      availableQuantity: 1,
    });

    const upload = buildMultipartBody({
      fieldName: 'image',
      filename: 'component.png',
      contentType: 'image/png',
      bytes: VALID_PNG,
    });

    const response = await app.inject({
      method: 'POST',
      url: `/components/${component.id}/image`,
      headers: {
        authorization: `Bearer ${adminToken}`,
        'content-type': upload.contentType,
      },
      payload: upload.body,
    });

    assert.equal(response.statusCode, 200);
    assert.ok(response.json().imageUrl.endsWith('.webp'));

    await deleteComponents([component.id]);
  });

  test('rejects oversized images', async () => {
    const component = await createComponent({
      name: 'Oversized Image Component',
      totalQuantity: 1,
      availableQuantity: 1,
    });

    const jpegMagic = Buffer.from([0xff, 0xd8, 0xff]);
    const oversized = Buffer.alloc(11 * 1024 * 1024 + 1, 0x11);
    const upload = buildMultipartBody({
      fieldName: 'image',
      filename: 'component.jpg',
      contentType: 'image/jpeg',
      bytes: Buffer.concat([jpegMagic, oversized]),
    });

    const response = await app.inject({
      method: 'POST',
      url: `/components/${component.id}/image`,
      headers: {
        authorization: `Bearer ${adminToken}`,
        'content-type': upload.contentType,
      },
      payload: upload.body,
    });

    assert.equal(response.statusCode, 413);
    assert.ok(response.json().error.includes('file too large'));

    await deleteComponents([component.id]);
  });
});

describe('Component image authorization', () => {
  let studentToken: string;
  let facultyToken: string;
  let taToken: string;
  let studentUserId: string;
  let facultyUserId: string;
  let taUserId: string;

  beforeAll(async () => {
    const { hash } = await import('bcryptjs');
    const passwordHash = await hash('password123', 12);

    const studentUser = await createUser({
      email: `image_student_${crypto.randomUUID()}@example.com`,
      passwordHash,
      name: 'Image Student',
      role: UserRole.STUDENT,
    });
    studentUserId = studentUser.id;
    studentToken = app.jwt.sign(
      { sub: studentUser.id, role: studentUser.role },
      { expiresIn: '1h' },
    );

    const facultyUser = await createUser({
      email: `image_faculty_${crypto.randomUUID()}@example.com`,
      passwordHash,
      name: 'Image Faculty',
      role: UserRole.FACULTY,
    });
    facultyUserId = facultyUser.id;
    facultyToken = app.jwt.sign(
      { sub: facultyUser.id, role: facultyUser.role },
      { expiresIn: '1h' },
    );

    const taUser = await createUser({
      email: `image_ta_${crypto.randomUUID()}@example.com`,
      passwordHash,
      name: 'Image LA',
      role: UserRole.LA,
    });
    taUserId = taUser.id;
    taToken = app.jwt.sign({ sub: taUser.id, role: taUser.role }, { expiresIn: '1h' });
  });

  afterAll(async () => {
    await deleteUsers([studentUserId, facultyUserId, taUserId].filter(Boolean));
  });

  test('upload returns 401 without token', async () => {
    const component = await createComponent({
      name: 'Auth Component',
      totalQuantity: 1,
      availableQuantity: 1,
    });

    const upload = buildMultipartBody({
      fieldName: 'image',
      filename: 'component.png',
      contentType: 'image/png',
      bytes: VALID_PNG,
    });

    const response = await app.inject({
      method: 'POST',
      url: `/components/${component.id}/image`,
      headers: { 'content-type': upload.contentType },
      payload: upload.body,
    });

    assert.equal(response.statusCode, 401);

    await deleteComponents([component.id]);
  });

  test('upload returns 403 for STUDENT', async () => {
    const component = await createComponent({
      name: 'Auth Component',
      totalQuantity: 1,
      availableQuantity: 1,
    });

    const upload = buildMultipartBody({
      fieldName: 'image',
      filename: 'component.png',
      contentType: 'image/png',
      bytes: VALID_PNG,
    });

    const response = await app.inject({
      method: 'POST',
      url: `/components/${component.id}/image`,
      headers: {
        authorization: `Bearer ${studentToken}`,
        'content-type': upload.contentType,
      },
      payload: upload.body,
    });

    assert.equal(response.statusCode, 403);

    await deleteComponents([component.id]);
  });

  test('upload returns 403 for FACULTY', async () => {
    const component = await createComponent({
      name: 'Auth Component',
      totalQuantity: 1,
      availableQuantity: 1,
    });

    const upload = buildMultipartBody({
      fieldName: 'image',
      filename: 'component.png',
      contentType: 'image/png',
      bytes: VALID_PNG,
    });

    const response = await app.inject({
      method: 'POST',
      url: `/components/${component.id}/image`,
      headers: {
        authorization: `Bearer ${facultyToken}`,
        'content-type': upload.contentType,
      },
      payload: upload.body,
    });

    assert.equal(response.statusCode, 403);

    await deleteComponents([component.id]);
  });

  test('upload returns 200 for LA', async () => {
    const component = await createComponent({
      name: 'Auth Component',
      totalQuantity: 1,
      availableQuantity: 1,
    });

    const upload = buildMultipartBody({
      fieldName: 'image',
      filename: 'component.png',
      contentType: 'image/png',
      bytes: VALID_PNG,
    });

    const response = await app.inject({
      method: 'POST',
      url: `/components/${component.id}/image`,
      headers: {
        authorization: `Bearer ${taToken}`,
        'content-type': upload.contentType,
      },
      payload: upload.body,
    });

    assert.equal(response.statusCode, 200);
    assert.ok(response.json().imageUrl.includes('/uploads/images/'));

    await deleteComponents([component.id]);
  });

  test('upload returns 404 for missing component', async () => {
    const upload = buildMultipartBody({
      fieldName: 'image',
      filename: 'component.png',
      contentType: 'image/png',
      bytes: VALID_PNG,
    });

    const response = await app.inject({
      method: 'POST',
      url: `/components/missing-component-id/image`,
      headers: {
        authorization: `Bearer ${adminToken}`,
        'content-type': upload.contentType,
      },
      payload: upload.body,
    });

    assert.equal(response.statusCode, 404);
  });

  test('upload returns 400 when no file field is present', async () => {
    const component = await createComponent({
      name: 'No File Component',
      totalQuantity: 1,
      availableQuantity: 1,
    });

    const boundary = `----iiitnr-${crypto.randomUUID()}`;
    const body = Buffer.from(
      `--${boundary}\r\nContent-Disposition: form-data; name="other"\r\n\r\nvalue\r\n--${boundary}--\r\n`,
    );

    const response = await app.inject({
      method: 'POST',
      url: `/components/${component.id}/image`,
      headers: {
        authorization: `Bearer ${adminToken}`,
        'content-type': `multipart/form-data; boundary=${boundary}`,
      },
      payload: body,
    });

    assert.equal(response.statusCode, 400);
    assert.ok(response.json().error.includes('no file uploaded'));

    await deleteComponents([component.id]);
  });

  test('delete returns 401 without token', async () => {
    const component = await createComponent({
      name: 'Auth Component',
      totalQuantity: 1,
      availableQuantity: 1,
    });

    const response = await app.inject({
      method: 'DELETE',
      url: `/components/${component.id}/image`,
    });

    assert.equal(response.statusCode, 401);

    await deleteComponents([component.id]);
  });

  test('delete returns 403 for STUDENT', async () => {
    const component = await createComponent({
      name: 'Auth Component',
      totalQuantity: 1,
      availableQuantity: 1,
    });

    const response = await app.inject({
      method: 'DELETE',
      url: `/components/${component.id}/image`,
      headers: { authorization: `Bearer ${studentToken}` },
    });

    assert.equal(response.statusCode, 403);

    await deleteComponents([component.id]);
  });

  test('delete returns 404 for missing component', async () => {
    const response = await app.inject({
      method: 'DELETE',
      url: `/components/missing-component-id/image`,
      headers: { authorization: `Bearer ${adminToken}` },
    });

    assert.equal(response.statusCode, 404);
  });

  test('delete returns 400 when component has no image', async () => {
    const component = await createComponent({
      name: 'No Image Component',
      totalQuantity: 1,
      availableQuantity: 1,
    });

    const response = await app.inject({
      method: 'DELETE',
      url: `/components/${component.id}/image`,
      headers: { authorization: `Bearer ${adminToken}` },
    });

    assert.equal(response.statusCode, 400);
    assert.ok(response.json().error.includes('no image'));

    await deleteComponents([component.id]);
  });

  test('delete returns 200 for LA', async () => {
    const component = await createComponent({
      name: 'Delete Image Component',
      totalQuantity: 1,
      availableQuantity: 1,
    });

    const upload = buildMultipartBody({
      fieldName: 'image',
      filename: 'component.png',
      contentType: 'image/png',
      bytes: VALID_PNG,
    });

    const uploadResponse = await app.inject({
      method: 'POST',
      url: `/components/${component.id}/image`,
      headers: {
        authorization: `Bearer ${adminToken}`,
        'content-type': upload.contentType,
      },
      payload: upload.body,
    });

    assert.equal(uploadResponse.statusCode, 200);

    const deleteResponse = await app.inject({
      method: 'DELETE',
      url: `/components/${component.id}/image`,
      headers: { authorization: `Bearer ${taToken}` },
    });

    assert.equal(deleteResponse.statusCode, 200);
    assert.equal(deleteResponse.json().component.imageUrl, null);

    await deleteComponents([component.id]);
  });
});

describe('Component image URL validation', () => {
  test('rejects javascript protocol on create', async () => {
    const response = await app.inject({
      method: 'POST',
      url: '/components',
      headers: { authorization: `Bearer ${adminToken}` },
      payload: {
        name: 'Invalid URL Component',
        imageUrl: 'javascript:alert(1)',
        totalQuantity: 1,
      },
    });

    assert.equal(response.statusCode, 400);
    assert.ok(response.json().error.includes('imageUrl must be a valid HTTP or HTTPS URL'));
  });

  test('accepts relative /uploads/images/ path on create', async () => {
    const response = await app.inject({
      method: 'POST',
      url: '/components',
      headers: { authorization: `Bearer ${adminToken}` },
      payload: {
        name: 'Relative Image Component',
        imageUrl: '/api/uploads/images/abc-123.png',
        totalQuantity: 1,
      },
    });

    assert.equal(response.statusCode, 201);
    const body = response.json();
    assert.equal(body.component.imageUrl, '/api/uploads/images/abc-123.png');

    await deleteComponents([body.component.id]);
  });

  test('accepts relative /uploads/images/ path on update', async () => {
    const component = await createComponent({
      name: 'Update Relative Image',
      totalQuantity: 1,
      availableQuantity: 1,
    });

    const response = await app.inject({
      method: 'PUT',
      url: `/components/${component.id}`,
      headers: { authorization: `Bearer ${adminToken}` },
      payload: {
        imageUrl: '/api/uploads/images/def-456.jpg',
      },
    });

    assert.equal(response.statusCode, 200);
    assert.equal(response.json().component.imageUrl, '/api/uploads/images/def-456.jpg');

    await deleteComponents([component.id]);
  });
});
