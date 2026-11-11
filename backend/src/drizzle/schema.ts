import {
  pgTable,
  varchar,
  timestamp,
  text,
  integer,
  boolean,
  jsonb,
  uniqueIndex,
  index,
  foreignKey,
  pgEnum,
} from 'drizzle-orm/pg-core';
import { sql } from 'drizzle-orm';

export const componentCategory = pgEnum('ComponentCategory', [
  'Sensors',
  'Actuators',
  'Microcontrollers',
  'Microprocessors',
  'Others',
]);
export const location = pgEnum('Location', ['IoT_Lab', 'Robo_Lab', 'VLSI_Lab']);
export const requestStatus = pgEnum('RequestStatus', [
  'PENDING',
  'APPROVED',
  'REJECTED',
  'ISSUED',
  'PARTIALLY_ISSUED',
  'RETURNED',
  'PARTIALLY_RETURNED',
  'EXPIRED',
  'RENEWED',
  'REQUESTED_RENEW',
]);
export const userRole = pgEnum('UserRole', ['ADMIN', 'FACULTY', 'PENDING', 'STUDENT', 'LA']);

export const prismaMigrations = pgTable('_prisma_migrations', {
  id: varchar({ length: 36 }).primaryKey().notNull(),
  checksum: varchar({ length: 64 }).notNull(),
  finishedAt: timestamp('finished_at', { withTimezone: true, mode: 'string' }),
  migrationName: varchar('migration_name', { length: 255 }).notNull(),
  logs: text(),
  rolledBackAt: timestamp('rolled_back_at', { withTimezone: true, mode: 'string' }),
  startedAt: timestamp('started_at', { withTimezone: true, mode: 'string' }).defaultNow().notNull(),
  appliedStepsCount: integer('applied_steps_count').default(0).notNull(),
});

export const user = pgTable(
  'User',
  {
    id: text().primaryKey().notNull(),
    email: text().notNull(),
    name: text(),
    imageUrl: text(),
    passwordHash: text(),
    googleId: text(),
    role: userRole().default('PENDING').notNull(),
    batch: text(),
    branch: text(),
    createdAt: timestamp({ precision: 3, mode: 'string' })
      .default(sql`CURRENT_TIMESTAMP`)
      .notNull(),
    updatedAt: timestamp({ precision: 3, mode: 'string' }).notNull(),
  },
  (table) => [
    uniqueIndex('User_email_key').using('btree', table.email.asc().nullsLast().op('text_ops')),
    uniqueIndex('User_googleId_key').using(
      'btree',
      table.googleId.asc().nullsLast().op('text_ops'),
    ),
  ],
);

export const request = pgTable(
  'Request',
  {
    id: text().primaryKey().notNull(),
    userId: text().notNull(),
    targetFacultyId: text().notNull(),
    projectTitle: text().notNull(),
    status: requestStatus().default('PENDING').notNull(),
    createdAt: timestamp({ precision: 3, mode: 'string' })
      .default(sql`CURRENT_TIMESTAMP`)
      .notNull(),
    updatedAt: timestamp({ precision: 3, mode: 'string' }).notNull(),
    returnDueAt: timestamp({ precision: 3, mode: 'string' }),
    returnedAt: timestamp({ precision: 3, mode: 'string' }),
    fulfilledAt: timestamp({ precision: 3, mode: 'string' }),
    receivedByUserId: text(),
    lastRenewDate: timestamp({ precision: 3, mode: 'string' }),
    lastRenewReason: text(),
    renewalReminderSentAt: timestamp({ precision: 3, mode: 'string' }),
    renewalRequestedAt: timestamp({ precision: 3, mode: 'string' }),
    lateFeeAccrued: integer().default(0).notNull(),
  },
  (table) => [
    index('Request_targetFacultyId_idx').using(
      'btree',
      table.targetFacultyId.asc().nullsLast().op('text_ops'),
    ),
    index('Request_userId_idx').using('btree', table.userId.asc().nullsLast().op('text_ops')),
    index('Request_status_idx').using('btree', table.status.asc().nullsLast()),
    foreignKey({
      columns: [table.targetFacultyId],
      foreignColumns: [user.id],
      name: 'Request_targetFacultyId_fkey',
    })
      .onUpdate('cascade')
      .onDelete('restrict'),
    foreignKey({
      columns: [table.userId],
      foreignColumns: [user.id],
      name: 'Request_userId_fkey',
    })
      .onUpdate('cascade')
      .onDelete('restrict'),
  ],
);

export const component = pgTable('Component', {
  id: text().primaryKey().notNull(),
  name: text().notNull(),
  description: text(),
  imageUrl: text(),
  totalQuantity: integer().default(0).notNull(),
  availableQuantity: integer().default(0).notNull(),
  category: componentCategory(),
  location: location(),
  createdAt: timestamp({ precision: 3, mode: 'string' })
    .default(sql`CURRENT_TIMESTAMP`)
    .notNull(),
  updatedAt: timestamp({ precision: 3, mode: 'string' }).notNull(),
});

export const requestItem = pgTable(
  'RequestItem',
  {
    id: text().primaryKey().notNull(),
    requestId: text().notNull(),
    componentId: text().notNull(),
    quantity: integer().notNull(),
    fulfilledQuantity: integer().default(0).notNull(),
    returnedQuantity: integer().default(0).notNull(),
    writtenOffQuantity: integer().default(0).notNull(),
    createdAt: timestamp({ precision: 3, mode: 'string' })
      .default(sql`CURRENT_TIMESTAMP`)
      .notNull(),
    updatedAt: timestamp({ precision: 3, mode: 'string' }).notNull(),
  },
  (table) => [
    index('RequestItem_componentId_idx').using(
      'btree',
      table.componentId.asc().nullsLast().op('text_ops'),
    ),
    index('RequestItem_requestId_idx').using(
      'btree',
      table.requestId.asc().nullsLast().op('text_ops'),
    ),
    foreignKey({
      columns: [table.componentId],
      foreignColumns: [component.id],
      name: 'RequestItem_componentId_fkey',
    })
      .onUpdate('cascade')
      .onDelete('restrict'),
    foreignKey({
      columns: [table.requestId],
      foreignColumns: [request.id],
      name: 'RequestItem_requestId_fkey',
    })
      .onUpdate('cascade')
      .onDelete('restrict'),
  ],
);

export const notification = pgTable(
  'Notification',
  {
    id: text().primaryKey().notNull(),
    recipientId: text('recipientId').notNull(),
    type: text().notNull(),
    title: text().notNull(),
    body: text(),
    requestId: text(),
    read: boolean().default(false).notNull(),
    createdAt: timestamp({ precision: 3, mode: 'string' })
      .default(sql`CURRENT_TIMESTAMP`)
      .notNull(),
  },
  (table) => [
    index('Notification_recipientId_createdAt_idx').using(
      'btree',
      table.recipientId.asc().nullsLast(),
      table.createdAt.asc().nullsLast(),
    ),
    index('Notification_recipientId_read_idx').using(
      'btree',
      table.recipientId.asc().nullsLast(),
      table.read.asc().nullsLast(),
    ),
    foreignKey({
      columns: [table.recipientId],
      foreignColumns: [user.id],
      name: 'Notification_recipientId_fkey',
    })
      .onUpdate('cascade')
      .onDelete('cascade'),
    foreignKey({
      columns: [table.requestId],
      foreignColumns: [request.id],
      name: 'Notification_requestId_fkey',
    })
      .onUpdate('cascade')
      .onDelete('set null'),
  ],
);

export const auditActionType = pgEnum('AuditActionType', [
  'CREATE',
  'UPDATE',
  'DELETE',
  'LOGIN',
  'LOGOUT',
  'REQUEST_STATUS_CHANGE',
  'INVENTORY_ADJUST',
]);

export const auditLog = pgTable(
  'AuditLog',
  {
    id: text().primaryKey().notNull(),
    userId: text(),
    action: auditActionType().notNull(),
    entityType: text(),
    entityId: text(),
    oldValues: text(),
    newValues: text(),
    ipAddress: varchar({ length: 45 }),
    userAgent: text(),
    metadata: text(),
    createdAt: timestamp({ precision: 3, mode: 'string' })
      .default(sql`CURRENT_TIMESTAMP`)
      .notNull(),
  },
  (table) => [
    index('AuditLog_userId_idx').using('btree', table.userId.asc().nullsLast().op('text_ops')),
    index('AuditLog_entityType_idx').using(
      'btree',
      table.entityType.asc().nullsLast().op('text_ops'),
    ),
    index('AuditLog_entityId_idx').using('btree', table.entityId.asc().nullsLast().op('text_ops')),
    index('AuditLog_createdAt_idx').using(
      'btree',
      table.createdAt.asc().nullsLast().op('timestamp_ops'),
    ),
    foreignKey({
      columns: [table.userId],
      foreignColumns: [user.id],
      name: 'AuditLog_userId_fkey',
    })
      .onUpdate('cascade')
      .onDelete('set null'),
  ],
);

export const requestEmailKind = pgEnum('RequestEmailKind', [
  'RENEWAL_CHASE',
  'EXPIRY_NOTICE',
  'LATE_FEE',
  'RENEWAL_REMINDER',
]);

export const requestEmail = pgTable(
  'RequestEmail',
  {
    id: text().primaryKey().notNull(),
    requestId: text().notNull(),
    recipientId: text().notNull(),
    kind: requestEmailKind().notNull(),
    variant: text().notNull(),
    subject: text(),
    metadata: jsonb(),
    sentAt: timestamp({ precision: 3, mode: 'string' })
      .default(sql`CURRENT_TIMESTAMP`)
      .notNull(),
  },
  (table) => [
    uniqueIndex('RequestEmail_dedupe_key').using(
      'btree',
      table.requestId.asc().nullsLast().op('text_ops'),
      table.recipientId.asc().nullsLast().op('text_ops'),
      table.kind.asc().nullsLast(),
      table.variant.asc().nullsLast().op('text_ops'),
    ),
    index('RequestEmail_requestId_idx').using(
      'btree',
      table.requestId.asc().nullsLast().op('text_ops'),
    ),
    index('RequestEmail_sentAt_idx').using('btree', table.sentAt.asc().nullsLast()),
    foreignKey({
      columns: [table.requestId],
      foreignColumns: [request.id],
      name: 'RequestEmail_requestId_fkey',
    })
      .onUpdate('cascade')
      .onDelete('cascade'),
    foreignKey({
      columns: [table.recipientId],
      foreignColumns: [user.id],
      name: 'RequestEmail_recipientId_fkey',
    })
      .onUpdate('cascade')
      .onDelete('cascade'),
  ],
);
