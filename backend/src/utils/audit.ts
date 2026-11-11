import type { FastifyRequest } from 'fastify';
import { db } from '../drizzle/db.js';
import { auditLog } from '../drizzle/schema.js';
import type { AuditActionTypeValue } from './enums.js';
import { getClientInfo } from './validation.js';

export interface AuditLogData {
  userId?: string;
  action: AuditActionTypeValue;
  entityType?: string;
  entityId?: string;
  oldValues?: Record<string, unknown>;
  newValues?: Record<string, unknown>;
  metadata?: Record<string, unknown>;
}

export async function logAudit(data: AuditLogData, request?: FastifyRequest): Promise<void> {
  try {
    const client = request ? getClientInfo(request) : undefined;

    await db.insert(auditLog).values({
      id: crypto.randomUUID(),
      userId: data.userId,
      action: data.action,
      entityType: data.entityType,
      entityId: data.entityId,
      oldValues: data.oldValues ? JSON.stringify(data.oldValues) : undefined,
      newValues: data.newValues ? JSON.stringify(data.newValues) : undefined,
      ipAddress: client?.ip,
      userAgent: client?.userAgent,
      metadata: data.metadata ? JSON.stringify(data.metadata) : undefined,
      createdAt: new Date().toISOString(),
    });
  } catch (err) {
    console.error('Failed to write audit log:', err);
  }
}

export function getUserIdFromRequest(request: FastifyRequest): string | undefined {
  return (request.user as { sub?: string })?.sub;
}
