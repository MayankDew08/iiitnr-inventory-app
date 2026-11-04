import type { FastifyPluginCallback, FastifyRequest, FastifyReply } from 'fastify';
import { eq, sql } from 'drizzle-orm';
import { db } from '../drizzle/db.js';
import { component, requestItem } from '../drizzle/schema.js';
import type { InferSelectModel } from 'drizzle-orm';
import { requireAuth, requireAdminOrLA } from '../middleware/auth.js';
import {
  UserRole,
  categoryValues,
  locationValues,
  toLocationEnum,
  AuditActionType,
} from '../utils/enums.js';
import type { CategoryValue, LocationValue, UserRoleValue } from '../utils/enums.js';
import { logAudit, getUserIdFromRequest } from '../utils/audit.js';
import { isValidHttpUrl, sanitizeString } from '../utils/validation.js';
import { parseCsv } from '../utils/csv.js';

interface ComponentBody {
  name?: string;
  description?: string;
  imageUrl?: string;
  totalQuantity?: number;
  availableQuantity?: number;
  category?: string;
  location?: string;
}

interface NormalizedComponentInput {
  name?: string;
  description?: string;
  imageUrl?: string;
  totalQuantity?: number;
  availableQuantity?: number;
  category?: string;
  location?: string;
}

interface ValidationError {
  code: number;
  message: string;
}

type ComponentRecord = InferSelectModel<typeof component>;

const componentBodySchema = {
  type: 'object',
  properties: {
    name: { type: 'string', minLength: 1, maxLength: 200 },
    description: { type: 'string', maxLength: 2000 },
    imageUrl: { type: 'string', maxLength: 2000 },
    totalQuantity: { type: 'integer', minimum: 0 },
    availableQuantity: { type: 'integer', minimum: 0 },
    category: { type: 'string', maxLength: 100 },
    location: { type: 'string', maxLength: 100 },
  },
  additionalProperties: false,
} as const;

const createComponentSchema = {
  body: { ...componentBodySchema, required: ['name'] as ['name'] },
} as const;

const updateComponentSchema = {
  body: componentBodySchema,
} as const;

function normalizeComponentInput(body: ComponentBody): NormalizedComponentInput {
  return {
    name: sanitizeString(body?.name),
    description: sanitizeString(body?.description),
    imageUrl: body?.imageUrl?.trim(),
    totalQuantity: body?.totalQuantity,
    availableQuantity: body?.availableQuantity,
    category: body?.category?.trim(),
    location: body?.location?.trim(),
  };
}

function validateQuantity(value: unknown, fieldName: string): ValidationError | null {
  if (value !== undefined && (typeof value !== 'number' || value < 0)) {
    return { code: 400, message: `${fieldName} must be a non-negative number` };
  }
  return null;
}

function validateCategory(category: string | undefined): ValidationError | null {
  if (category && !categoryValues.includes(category as CategoryValue)) {
    return {
      code: 400,
      message: `invalid category. Must be one of ${categoryValues.join(', ')} (use Others if none apply)`,
    };
  }
  return null;
}

function validateLocation(location: string | undefined): ValidationError | null {
  if (location && !locationValues.includes(location as LocationValue)) {
    return {
      code: 400,
      message: `invalid location. Must be one of ${locationValues.join(', ')}`,
    };
  }
  return null;
}

function validateImageUrl(imageUrl: string | undefined): ValidationError | null {
  if (imageUrl && !isValidHttpUrl(imageUrl)) {
    return { code: 400, message: 'imageUrl must be a valid HTTP or HTTPS URL' };
  }
  return null;
}

function validateCreateInput(input: NormalizedComponentInput): ValidationError | null {
  if (!input.name) {
    return { code: 400, message: 'name is required' };
  }

  return (
    validateQuantity(input.totalQuantity, 'totalQuantity') ||
    validateQuantity(input.availableQuantity, 'availableQuantity') ||
    validateCategory(input.category) ||
    validateLocation(input.location) ||
    validateImageUrl(input.imageUrl)
  );
}

function validateUpdateInput(input: NormalizedComponentInput): ValidationError | null {
  return (
    validateQuantity(input.totalQuantity, 'totalQuantity') ||
    validateQuantity(input.availableQuantity, 'availableQuantity') ||
    (input.category !== undefined && input.category !== ''
      ? validateCategory(input.category)
      : null) ||
    (input.location !== undefined && input.location !== ''
      ? validateLocation(input.location)
      : null) ||
    (input.imageUrl !== undefined && input.imageUrl !== ''
      ? validateImageUrl(input.imageUrl)
      : null)
  );
}

function validateQuantityRelationship(
  availableQuantity: number,
  totalQuantity: number,
): ValidationError | null {
  if (availableQuantity > totalQuantity) {
    return { code: 400, message: 'availableQuantity cannot be greater than totalQuantity' };
  }
  return null;
}

function findComponentById(id: string): Promise<ComponentRecord | undefined> {
  return db.query.component.findFirst({
    where: (c, { eq }) => eq(c.id, id),
  });
}

function getAllComponents(): Promise<ComponentRecord[]> {
  return db.query.component.findMany({
    orderBy: (components, { desc }) => [desc(components.createdAt)],
  });
}

async function getComponentUsageCount(componentId: string): Promise<number> {
  const [result] = await db
    .select({ count: sql<number>`count(*)::int` })
    .from(requestItem)
    .where(eq(requestItem.componentId, componentId));
  return result?.count ?? 0;
}

async function insertComponent(data: {
  name: string;
  description?: string;
  imageUrl?: string;
  totalQuantity: number;
  availableQuantity: number;
  category?: CategoryValue | null;
  location?: LocationValue | null;
}): Promise<ComponentRecord | undefined> {
  const now = new Date().toISOString();
  const [created] = await db
    .insert(component)
    .values({
      id: crypto.randomUUID(),
      name: data.name,
      description: data.description || null,
      imageUrl: data.imageUrl || null,
      totalQuantity: data.totalQuantity,
      availableQuantity: data.availableQuantity,
      category: data.category || null,
      location: data.location || null,
      createdAt: now,
      updatedAt: now,
    })
    .returning();
  return created;
}

type ComponentUpdateResult =
  | { ok: true; existing: ComponentRecord; updated: ComponentRecord }
  | { ok: false; status: 404 }
  | { ok: false; status: 400; message: string };

async function updateComponentInTransaction(
  id: string,
  input: NormalizedComponentInput,
): Promise<ComponentUpdateResult> {
  return db.transaction(async (tx) => {
    const [lockedComponent] = await tx
      .select()
      .from(component)
      .where(eq(component.id, id))
      .for('update');

    if (!lockedComponent) {
      return { ok: false as const, status: 404 as const };
    }

    const { data, nextAvailable, nextTotal } = buildUpdateData(input, lockedComponent);

    const quantityError = validateQuantityRelationship(nextAvailable, nextTotal);
    if (quantityError) {
      return { ok: false as const, status: 400 as const, message: quantityError.message };
    }

    const [updated] = await tx
      .update(component)
      .set({ ...data, updatedAt: new Date().toISOString() } as typeof component.$inferInsert)
      .where(eq(component.id, id))
      .returning();

    if (!updated) {
      throw new Error('failed to update component');
    }

    return { ok: true as const, existing: lockedComponent, updated };
  });
}

async function deleteComponentById(id: string): Promise<void> {
  await db.delete(component).where(eq(component.id, id));
}

function getLatestDateString(components: ComponentRecord[]): string {
  let latest = '';
  for (const c of components) {
    const dateStr = (c.updatedAt ?? c.createdAt) as string;
    if (dateStr > latest) {
      latest = dateStr;
    }
  }
  return latest;
}

function parseIsoDate(dateStr: string): Date {
  const isoStr = dateStr.includes('T') ? dateStr : dateStr.replace(' ', 'T');
  return new Date(isoStr.endsWith('Z') ? isoStr : `${isoStr}Z`);
}

function shouldReturn304(modifiedSinceHeader: string | undefined, latestDateStr: string): boolean {
  if (!modifiedSinceHeader) return false;

  const isoStr = latestDateStr.includes('T') ? latestDateStr : latestDateStr.replace(' ', 'T');
  const clientDate = new Date(modifiedSinceHeader);
  const serverSec = isoStr.substring(0, 19);
  const clientSec = clientDate.toISOString().substring(0, 19);

  return clientSec >= serverSec;
}

function escapeCsvValue(value: string | null | undefined): string {
  if (value == null) return '';
  const str = value.replace(/"/g, '""');
  return str.includes(',') || str.includes('"') || str.includes('\n') ? `"${str}"` : str;
}

function componentToCsvRow(c: ComponentRecord): string {
  return [
    escapeCsvValue(c.id),
    escapeCsvValue(c.name),
    escapeCsvValue(c.description),
    escapeCsvValue(c.category?.replace(/_/g, ' ') ?? null),
    escapeCsvValue(c.location?.replace(/_/g, ' ') ?? null),
    escapeCsvValue(c.imageUrl),
    escapeCsvValue(c.createdAt),
    escapeCsvValue(c.updatedAt),
    c.totalQuantity,
    c.availableQuantity,
  ].join(',');
}

function generateComponentsCsv(components: ComponentRecord[]): string {
  const header =
    'ID,Name,Description,Category,Location,Image URL,Created At,Updated At,Total Quantity,Available Quantity';
  const rows = components.map(componentToCsvRow);
  return [header, ...rows].join('\n');
}

function buildUpdateData(
  input: NormalizedComponentInput,
  existing: ComponentRecord,
): { data: Record<string, unknown>; nextAvailable: number; nextTotal: number } {
  const nextTotal =
    input.totalQuantity !== undefined ? input.totalQuantity : existing.totalQuantity;

  const nextAvailable =
    input.availableQuantity !== undefined
      ? input.availableQuantity
      : input.totalQuantity !== undefined
        ? input.totalQuantity
        : existing.availableQuantity;

  const data: Record<string, unknown> = {};

  if (input.name !== undefined) data.name = input.name;
  if (input.description !== undefined) data.description = input.description || null;
  if (input.imageUrl !== undefined) data.imageUrl = input.imageUrl || null;
  if (input.totalQuantity !== undefined) data.totalQuantity = nextTotal;
  if (input.availableQuantity !== undefined || input.totalQuantity !== undefined) {
    data.availableQuantity = nextAvailable;
  }
  if (input.category !== undefined) {
    data.category = input.category ? (input.category as CategoryValue) : null;
  }
  if (input.location !== undefined) {
    data.location = input.location ? toLocationEnum(input.location) : null;
  }

  return { data, nextAvailable, nextTotal };
}

function canExportCsv(role: UserRoleValue | undefined): boolean {
  return role === UserRole.ADMIN || role === UserRole.LA || role === UserRole.FACULTY;
}

const MAX_IMPORT_ROWS = 1000;

interface CsvRowError {
  row: number;
  message: string;
}

interface ValidCsvComponentInput {
  name: string;
  description?: string;
  imageUrl?: string;
  totalQuantity: number;
  availableQuantity: number;
  category?: string;
  location?: string;
}

type CsvCellMap = Partial<
  Record<
    | 'name'
    | 'description'
    | 'imageUrl'
    | 'totalQuantity'
    | 'availableQuantity'
    | 'category'
    | 'location',
    string
  >
>;

type DbTransaction = Parameters<Parameters<typeof db.transaction>[0]>[0];

function mapCsvHeader(header: string): keyof CsvCellMap | null {
  const normalized = header.trim().toLowerCase();
  switch (normalized) {
    case 'name':
      return 'name';
    case 'description':
      return 'description';
    case 'category':
      return 'category';
    case 'location':
      return 'location';
    case 'image url':
    case 'imageurl':
    case 'image_url':
      return 'imageUrl';
    case 'total quantity':
    case 'totalquantity':
      return 'totalQuantity';
    case 'available quantity':
    case 'availablequantity':
      return 'availableQuantity';
    default:
      return null;
  }
}

function parseQuantityCell(
  value: string | undefined,
  fieldName: string,
): { value?: number; error?: string } {
  const trimmed = value?.trim();
  if (trimmed === undefined || trimmed === '') {
    return {};
  }
  const parsed = Number(trimmed);
  if (!Number.isFinite(parsed) || !Number.isInteger(parsed) || parsed < 0) {
    return { error: `${fieldName} must be a non-negative integer` };
  }
  return { value: parsed };
}

function extractCsvContent(req: FastifyRequest): string | null {
  const csvContent =
    typeof req.body === 'string'
      ? req.body
      : typeof (req.body as { csv?: unknown } | null)?.csv === 'string'
        ? (req.body as { csv: string }).csv
        : undefined;

  if (!csvContent || csvContent.trim() === '') {
    return null;
  }

  return csvContent;
}

function validateCsvStructure(
  rows: string[][],
): { columnFields: (keyof CsvCellMap | null)[]; dataRows: string[][] } | { error: string } {
  const headerRow = rows[0];

  if (!headerRow) {
    return { error: 'CSV must contain a header row' };
  }

  const columnFields = headerRow.map(mapCsvHeader);

  if (!columnFields.includes('name')) {
    return { error: "CSV must contain a 'Name' column" };
  }

  const dataRows = rows.slice(1);

  if (dataRows.length > MAX_IMPORT_ROWS) {
    return { error: `CSV cannot contain more than ${MAX_IMPORT_ROWS} data rows` };
  }

  return { columnFields, dataRows };
}

function parseCsvRows(
  dataRows: string[][],
  columnFields: (keyof CsvCellMap | null)[],
): { validInputs: ValidCsvComponentInput[]; errors: CsvRowError[] } {
  const validInputs: ValidCsvComponentInput[] = [];
  const errors: CsvRowError[] = [];

  for (let i = 0; i < dataRows.length; i++) {
    const cells = dataRows[i];
    if (!cells) continue;
    const rowNumber = i + 1;

    if (cells.every((cell) => cell === undefined || cell.trim() === '')) continue;

    const cellMap: CsvCellMap = {};
    for (let c = 0; c < columnFields.length; c++) {
      const field = columnFields[c];
      if (field) {
        cellMap[field] = cells[c] ?? '';
      }
    }

    const input = normalizeComponentInput({
      name: cellMap.name,
      description: cellMap.description || undefined,
      imageUrl: cellMap.imageUrl || undefined,
      category: cellMap.category || undefined,
      location: cellMap.location || undefined,
    } as ComponentBody);

    if (!input.name) {
      errors.push({ row: rowNumber, message: 'name is required' });
      continue;
    }

    const totalParsed = parseQuantityCell(cellMap.totalQuantity, 'totalQuantity');
    if (totalParsed.error) {
      errors.push({ row: rowNumber, message: totalParsed.error });
      continue;
    }
    const availableParsed = parseQuantityCell(cellMap.availableQuantity, 'availableQuantity');
    if (availableParsed.error) {
      errors.push({ row: rowNumber, message: availableParsed.error });
      continue;
    }

    input.totalQuantity = totalParsed.value;
    input.availableQuantity = availableParsed.value ?? totalParsed.value;

    const validationError =
      validateCreateInput(input) ||
      validateQuantityRelationship(input.availableQuantity ?? 0, input.totalQuantity ?? 0);

    if (validationError) {
      errors.push({ row: rowNumber, message: validationError.message });
      continue;
    }

    validInputs.push(input as ValidCsvComponentInput);
  }

  return { validInputs, errors };
}

async function insertCsvComponents(
  tx: DbTransaction,
  inputs: ValidCsvComponentInput[],
): Promise<ComponentRecord[]> {
  const inserted: ComponentRecord[] = [];
  const now = new Date().toISOString();
  for (const input of inputs) {
    const [record] = await tx
      .insert(component)
      .values({
        id: crypto.randomUUID(),
        name: input.name,
        description: input.description || null,
        imageUrl: input.imageUrl || null,
        totalQuantity: input.totalQuantity ?? 0,
        availableQuantity: input.availableQuantity ?? input.totalQuantity ?? 0,
        category: input.category ? (input.category as CategoryValue) : null,
        location: input.location ? toLocationEnum(input.location) : null,
        createdAt: now,
        updatedAt: now,
      })
      .returning();
    if (!record) {
      throw new Error('failed to import component row');
    }
    inserted.push(record);
  }
  return inserted;
}

async function auditCsvImport(req: FastifyRequest, records: ComponentRecord[]): Promise<void> {
  for (const record of records) {
    await logAudit(
      {
        userId: getUserIdFromRequest(req),
        action: AuditActionType.CREATE,
        entityType: 'Component',
        entityId: record.id,
        newValues: record as Record<string, unknown>,
      },
      req,
    );
  }
}

async function handleImportComponentsCsv(
  app: { log: { error: (err: unknown) => void } },
  req: FastifyRequest,
  reply: FastifyReply,
) {
  const csvContent = extractCsvContent(req);

  if (!csvContent) {
    return reply.code(400).send({ error: 'CSV content is required' });
  }

  const structure = validateCsvStructure(parseCsv(csvContent));

  if ('error' in structure) {
    return reply.code(400).send({ error: structure.error });
  }

  const { validInputs, errors } = parseCsvRows(structure.dataRows, structure.columnFields);

  if (errors.length > 0) {
    return reply.code(400).send({ error: 'import failed', details: errors });
  }

  try {
    const created = await db.transaction((tx) => insertCsvComponents(tx, validInputs));

    await auditCsvImport(req, created);

    return reply.code(201).send({ imported: created.length, components: created });
  } catch (err) {
    app.log.error(err);
    return reply.code(500).send({ error: 'failed to import components' });
  }
}

async function handleGetComponents(
  app: { log: { error: (err: unknown) => void } },
  req: FastifyRequest,
  reply: FastifyReply,
) {
  try {
    const components = await getAllComponents();

    const latestDateStr = getLatestDateString(components);

    if (!latestDateStr) {
      return reply.send({ components });
    }

    if (shouldReturn304(req.headers['if-modified-since'], latestDateStr)) {
      return reply.code(304).send();
    }

    const lastModifiedHeader = parseIsoDate(latestDateStr).toUTCString();

    return reply
      .headers({
        'Last-Modified': lastModifiedHeader,
        'Cache-Control': 'private, max-age=0, must-revalidate',
      })
      .send({ components, lastModified: lastModifiedHeader });
  } catch (err) {
    app.log.error(err);
    return reply.code(500).send({ error: 'failed to fetch components' });
  }
}

async function handleExportCsv(
  app: { log: { error: (err: unknown) => void } },
  req: FastifyRequest,
  reply: FastifyReply,
) {
  const userRole = (req.user as { role?: UserRoleValue })?.role;

  if (!canExportCsv(userRole)) {
    return reply.code(403).send({ error: 'forbidden: admin, LA, or faculty role required' });
  }

  try {
    const components = await getAllComponents();
    const csv = generateComponentsCsv(components);

    return reply
      .header('Content-Type', 'text/csv')
      .header('Content-Disposition', 'attachment; filename="components.csv"')
      .send(csv);
  } catch (err) {
    app.log.error(err);
    return reply.code(500).send({ error: 'failed to export components' });
  }
}

async function handleGetComponentById(
  app: { log: { error: (err: unknown) => void } },
  req: FastifyRequest,
  reply: FastifyReply,
) {
  const { id } = req.params as { id?: string };

  if (!id) {
    return reply.code(400).send({ error: 'component id is required' });
  }

  try {
    const found = await findComponentById(id);

    if (!found) {
      return reply.code(404).send({ error: 'component not found' });
    }

    return reply.send({ component: found });
  } catch (err) {
    app.log.error(err);
    return reply.code(500).send({ error: 'failed to fetch component' });
  }
}

async function handleCreateComponent(
  app: { log: { error: (err: unknown) => void } },
  req: FastifyRequest,
  reply: FastifyReply,
) {
  const input = normalizeComponentInput(req.body as ComponentBody);
  const validationError = validateCreateInput(input);

  if (validationError) {
    return reply.code(validationError.code).send({ error: validationError.message });
  }

  const componentName = input.name;
  if (!componentName) {
    return reply.code(400).send({ error: 'name is required' });
  }

  try {
    const created = await insertComponent({
      name: componentName,
      description: input.description,
      imageUrl: input.imageUrl,
      totalQuantity: input.totalQuantity ?? 0,
      availableQuantity: input.availableQuantity ?? input.totalQuantity ?? 0,
      category: input.category ? (input.category as CategoryValue) : null,
      location: input.location ? toLocationEnum(input.location) : null,
    });

    if (!created) {
      return reply.code(500).send({ error: 'failed to create component' });
    }

    await logAudit(
      {
        userId: getUserIdFromRequest(req),
        action: AuditActionType.CREATE,
        entityType: 'Component',
        entityId: created.id,
        newValues: created as Record<string, unknown>,
      },
      req,
    );

    return reply.code(201).send({ component: created });
  } catch (err) {
    app.log.error(err);
    return reply.code(500).send({ error: 'failed to create component' });
  }
}

async function handleUpdateComponent(
  app: { log: { error: (err: unknown) => void } },
  req: FastifyRequest,
  reply: FastifyReply,
) {
  const { id } = req.params as { id?: string };

  if (!id) {
    return reply.code(400).send({ error: 'component id is required' });
  }

  const input = normalizeComponentInput(req.body as ComponentBody);
  const validationError = validateUpdateInput(input);

  if (validationError) {
    return reply.code(validationError.code).send({ error: validationError.message });
  }

  try {
    const result = await updateComponentInTransaction(id, input);

    if (!result.ok) {
      if (result.status === 404) {
        return reply.code(404).send({ error: 'component not found' });
      }
      return reply.code(400).send({ error: result.message });
    }

    const { existing, updated } = result;

    await logAudit(
      {
        userId: getUserIdFromRequest(req),
        action: AuditActionType.UPDATE,
        entityType: 'Component',
        entityId: updated.id,
        oldValues: existing as Record<string, unknown>,
        newValues: updated as Record<string, unknown>,
      },
      req,
    );

    return reply.send({ component: updated });
  } catch (err) {
    app.log.error(err);
    return reply.code(500).send({ error: 'failed to update component' });
  }
}

async function handleDeleteComponent(
  app: { log: { error: (err: unknown) => void } },
  req: FastifyRequest,
  reply: FastifyReply,
) {
  const { id } = req.params as { id?: string };

  if (!id) {
    return reply.code(400).send({ error: 'component id is required' });
  }

  try {
    const existing = await findComponentById(id);

    if (!existing) {
      return reply.code(404).send({ error: 'component not found' });
    }

    const usageCount = await getComponentUsageCount(id);

    if (usageCount > 0) {
      return reply.code(400).send({
        error: 'component cannot be deleted because it is used in one or more requests',
      });
    }

    await deleteComponentById(id);

    await logAudit(
      {
        userId: getUserIdFromRequest(req),
        action: AuditActionType.DELETE,
        entityType: 'Component',
        entityId: id,
        oldValues: existing as Record<string, unknown>,
      },
      req,
    );

    return reply.code(204).send();
  } catch (err) {
    app.log.error(err);
    return reply.code(500).send({ error: 'failed to delete component' });
  }
}

const componentsRoutes: FastifyPluginCallback = (app, _opts, done) => {
  app.get('/', { preHandler: requireAuth }, (req, reply) => handleGetComponents(app, req, reply));

  app.get('/export/csv', { preHandler: requireAuth }, (req, reply) =>
    handleExportCsv(app, req, reply),
  );

  app.post('/import/csv', { preHandler: requireAdminOrLA }, (req, reply) =>
    handleImportComponentsCsv(app, req, reply),
  );

  app.get('/:id', { preHandler: requireAuth }, (req, reply) =>
    handleGetComponentById(app, req, reply),
  );

  app.post('/', { preHandler: requireAdminOrLA, schema: createComponentSchema }, (req, reply) =>
    handleCreateComponent(app, req, reply),
  );

  app.put('/:id', { preHandler: requireAdminOrLA, schema: updateComponentSchema }, (req, reply) =>
    handleUpdateComponent(app, req, reply),
  );

  app.delete('/:id', { preHandler: requireAdminOrLA }, (req, reply) =>
    handleDeleteComponent(app, req, reply),
  );

  done();
};

export default componentsRoutes;
