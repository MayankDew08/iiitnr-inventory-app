import type { FastifyPluginCallback, FastifyInstance, FastifyRequest, FastifyReply } from 'fastify';
import { canExportCsv, requireAuth, isAdminOrLA } from '../middleware/auth.js';
import { RequestStatus, requestStatusValues, UserRole, AuditActionType } from '../utils/enums.js';
import type { RequestStatusValue, UserRoleValue } from '../utils/enums.js';
import { logAudit, getUserIdFromRequest } from '../utils/audit.js';
import { isValidationError, sanitizeString } from '../utils/validation.js';
import type { ValidationError } from '../utils/validation.js';
import { escapeCsvField } from '../utils/csv.js';
import { expireOverdueRequests } from '../services/request-expiry.js';
import { fanOutExpiredRequests } from '../services/expiry-fanout.js';
import { notifyRenewalRejected, notifyRequestTransition } from '../services/NotificationService.js';
import { events, EventType, notifyRequestsUpdated } from '../utils/events.js';
import type { EventMessage } from 'fastify-sse-v2';
import { pushable } from 'it-pushable';
import * as RequestService from '../services/RequestService.js';
import type {
  IssueItemInput,
  ReturnItemInput,
  RequestItemInput,
  RequestWithRelations,
} from '../services/RequestService.js';

interface CurrentUser {
  sub?: string;
  role?: UserRoleValue;
}

interface CreateRequestBody {
  items?: RequestItemInput[];
  targetFacultyId?: string;
  projectTitle?: string;
}

interface RequestQuery {
  userId?: string;
  status?: string;
}

interface NormalizedItem {
  componentId: string;
  quantity: number;
}

interface UpdateStatusBody {
  status?: string;
  lastRenewReason?: string;
  issueItems?: IssueItemInput[];
  returnItems?: ReturnItemInput[];
}

const createRequestSchema = {
  body: {
    type: 'object',
    properties: {
      items: {
        type: 'array',
        minItems: 1,
        maxItems: 100,
        items: {
          type: 'object',
          properties: {
            componentId: { type: 'string', maxLength: 100 },
            quantity: { type: 'integer', minimum: 1 },
          },
          additionalProperties: false,
        },
      },
      targetFacultyId: { type: 'string', maxLength: 100 },
      projectTitle: { type: 'string', minLength: 1, maxLength: 300 },
    },
    additionalProperties: false,
  },
} as const;

const updateRequestStatusSchema = {
  body: {
    type: 'object',
    required: ['status'],
    properties: {
      status: { type: 'string', maxLength: 50 },
      lastRenewReason: { type: 'string', maxLength: 500 },
      issueItems: {
        type: 'array',
        items: {
          type: 'object',
          properties: {
            componentId: { type: 'string', maxLength: 100 },
            quantity: { type: 'integer', minimum: 1 },
          },
          required: ['componentId', 'quantity'],
          additionalProperties: false,
        },
      },
      returnItems: {
        type: 'array',
        items: {
          type: 'object',
          properties: {
            componentId: { type: 'string', maxLength: 100 },
            quantity: { type: 'integer', minimum: 1 },
          },
          required: ['componentId', 'quantity'],
          additionalProperties: false,
        },
      },
    },
    additionalProperties: false,
  },
} as const;

function getCurrentUser(req: FastifyRequest): CurrentUser {
  return req.user as CurrentUser;
}

function validateCreateRequestBody(body: CreateRequestBody): ValidationError | null {
  const items = body?.items ?? [];
  const targetFacultyId = body?.targetFacultyId?.trim();
  const projectTitle = body?.projectTitle?.trim();

  if (!Array.isArray(items) || items.length === 0) {
    return { code: 400, message: 'items are required' };
  }
  if (!targetFacultyId) {
    return { code: 400, message: 'targetFacultyId is required' };
  }
  if (!projectTitle) {
    return { code: 400, message: 'projectTitle is required' };
  }
  return null;
}

function normalizeAndValidateItems(items: RequestItemInput[]): NormalizedItem[] | ValidationError {
  const normalized: NormalizedItem[] = [];

  for (const item of items) {
    const componentId = item?.componentId?.trim();
    const quantity = item?.quantity;

    if (!componentId) {
      return { code: 400, message: 'componentId is required' };
    }
    if (typeof quantity !== 'number' || quantity <= 0) {
      return { code: 400, message: 'quantity must be a positive number' };
    }
    normalized.push({ componentId, quantity });
  }

  const componentIds = normalized.map((item) => item.componentId);
  if (new Set(componentIds).size !== componentIds.length) {
    return { code: 400, message: 'duplicate componentId in request' };
  }

  return normalized;
}

function validateStatusInput(status: string | undefined): ValidationError | null {
  const trimmed = status?.trim();

  if (!trimmed) {
    return { code: 400, message: 'status is required' };
  }
  if (!requestStatusValues.includes(trimmed as RequestStatusValue)) {
    return { code: 400, message: 'invalid status' };
  }

  return null;
}

function toNotificationShapedRequest(
  request: NonNullable<Awaited<ReturnType<typeof RequestService.fetchAndShapeRequest>>>,
) {
  return { ...request, items: request.items ?? [] };
}

async function notifyTransitionSafely(
  app: { log: { error: (err: unknown) => void } },
  updatedRequest: NonNullable<Awaited<ReturnType<typeof RequestService.fetchAndShapeRequest>>>,
  actorUserId?: string,
) {
  try {
    await notifyRequestTransition(toNotificationShapedRequest(updatedRequest), actorUserId);
  } catch (err) {
    app.log.error(err);
  }
}

function canRenewApproveOrRejectRequest(
  currentUser: CurrentUser,
  existingRequest: { targetFacultyId: string | null },
): ValidationError | null {
  if (currentUser.role === UserRole.FACULTY) {
    if (existingRequest.targetFacultyId !== currentUser.sub) {
      return { code: 403, message: 'forbidden: can only approve/reject requests targeting you' };
    }
    return null;
  }

  if (!isAdminOrLA(currentUser.role)) {
    return {
      code: 403,
      message: 'forbidden: only faculty, admin, or LA can approve/reject requests',
    };
  }

  return null;
}

function canIssueRequest(currentUser: CurrentUser): ValidationError | null {
  if (!isAdminOrLA(currentUser.role)) {
    return { code: 403, message: 'forbidden: only admin or LA can issue requests' };
  }
  return null;
}

function canRequestRenewal(
  currentUser: CurrentUser,
  existingRequest: { userId: string },
): ValidationError | null {
  if (existingRequest.userId !== currentUser.sub) {
    return { code: 403, message: 'forbidden: can only request renewal for your own request' };
  }
  return null;
}

function statusBeforeRenewal(
  items: { quantity: number; fulfilledQuantity: number | null }[],
): RequestStatusValue {
  const fullyIssued = items.every((item) => (item.fulfilledQuantity ?? 0) >= item.quantity);
  return fullyIssued ? RequestStatus.ISSUED : RequestStatus.PARTIALLY_ISSUED;
}

function canDeleteRequest(
  currentUser: CurrentUser,
  existingRequest: { userId: string; status: string },
): ValidationError | null {
  const isOwner = existingRequest.userId === currentUser.sub;
  const isPrivileged = isAdminOrLA(currentUser.role);

  if (!isOwner && !isPrivileged) {
    return { code: 403, message: 'forbidden: cannot delete this request' };
  }
  if (existingRequest.status !== RequestStatus.PENDING) {
    return { code: 400, message: 'request can only be deleted when status is PENDING' };
  }
  return null;
}

async function handleCreateRequest(
  app: { log: { error: (err: unknown) => void } },
  req: FastifyRequest,
  reply: FastifyReply,
) {
  const body = req.body as CreateRequestBody;
  const userId = getCurrentUser(req)?.sub;
  if (!userId) {
    reply.code(401).send({ error: 'unauthorized' });
    return;
  }

  const bodyError = validateCreateRequestBody(body);
  if (bodyError) {
    reply.code(bodyError.code).send({ error: bodyError.message });
    return;
  }

  const targetFacultyId = body.targetFacultyId?.trim();
  const projectTitle = sanitizeString(body.projectTitle);
  const rawItems = body.items;
  if (!targetFacultyId || !projectTitle || !Array.isArray(rawItems)) {
    reply.code(400).send({ error: 'invalid request body' });
    return;
  }

  const itemsResult = normalizeAndValidateItems(rawItems);
  if (isValidationError(itemsResult)) {
    reply.code(itemsResult.code).send({ error: itemsResult.message });
    return;
  }
  const normalizedItems = itemsResult;

  if (!(await RequestService.validateFacultyExists(targetFacultyId))) {
    await reply.code(400).send({ error: 'invalid targetFacultyId' });
    return;
  }

  const componentIds = normalizedItems.map((item) => item.componentId);
  if (!(await RequestService.validateComponentsExist(componentIds))) {
    await reply.code(400).send({ error: 'one or more components not found' });
    return;
  }

  const requestId = await RequestService.createRequest(
    userId,
    targetFacultyId,
    projectTitle,
    normalizedItems,
  );

  const createdRequest = await RequestService.fetchExactAndShapeRequest(requestId);
  if (!createdRequest) {
    throw new Error('failed to create request');
  }

  await logAudit(
    {
      userId: getUserIdFromRequest(req),
      action: AuditActionType.CREATE,
      entityType: 'Request',
      entityId: requestId,
      newValues: createdRequest as Record<string, unknown>,
    },
    req,
  );

  await notifyTransitionSafely(app, createdRequest, userId);

  reply.code(201).send({ request: createdRequest });
  notifyRequestsUpdated();
}

async function handleGetFaculty(
  app: { log: { error: (err: unknown) => void } },
  reply: FastifyReply,
) {
  const faculty = await RequestService.getFaculty();
  reply.send({ faculty });
}

async function handleGetRequests(
  app: { log: { error: (err: unknown) => void } },
  req: FastifyRequest,
  reply: FastifyReply,
) {
  const currentUser = getCurrentUser(req);
  const currentUserId = currentUser?.sub;
  if (!currentUserId) {
    reply.code(401).send({ error: 'unauthorized' });
    return;
  }

  const query = req.query as RequestQuery;
  const requestedUserId = query?.userId?.trim();
  const status = query?.status?.trim();

  if (status && !requestStatusValues.includes(status as RequestStatusValue)) {
    reply.code(400).send({ error: 'invalid status' });
    return;
  }

  if (status === RequestStatus.EXPIRED) {
    const expired = await expireOverdueRequests();
    fanOutExpiredRequests(expired, (err, message) => req.log.error({ err }, message));
  }

  const isAdmin = isAdminOrLA(currentUser.role);
  const isFaculty = currentUser.role === UserRole.FACULTY;

  const requests = await RequestService.getRequests({
    userId: isAdmin ? requestedUserId : currentUserId,
    targetFacultyId: isFaculty ? currentUserId : undefined,
    status: status as RequestStatusValue,
  });

  reply.send({ requests });
}

async function handleGetRequestById(
  app: { log: { error: (err: unknown) => void } },
  req: FastifyRequest<{ Params: { id: string } }>,
  reply: FastifyReply,
) {
  const currentUser = getCurrentUser(req);
  const currentUserId = currentUser?.sub;
  if (!currentUserId) {
    reply.code(401).send({ error: 'unauthorized' });
    return;
  }

  const params = req.params as { id?: string };
  const id = params?.id;
  if (!id) {
    reply.code(400).send({ error: 'request id is required' });
    return;
  }

  const shaped = await RequestService.fetchAndShapeRequest(id);
  if (!shaped) {
    reply.code(404).send({ error: 'request not found' });
    return;
  }

  const isAdminOrLAUser = isAdminOrLA(currentUser.role);

  if (
    !isAdminOrLAUser &&
    shaped.userId !== currentUserId &&
    shaped.targetFacultyId !== currentUserId
  ) {
    reply.code(403).send({ error: 'forbidden' });
    return;
  }

  reply.send({ request: shaped });
}

async function handlePendingStatusUpdate(
  req: FastifyRequest,
  reply: FastifyReply,
  existingRequest: NonNullable<RequestWithRelations>,
  newStatus: RequestStatusValue,
  currentUser: CurrentUser,
) {
  const allowedPendingTransitions: RequestStatusValue[] = [
    RequestStatus.REJECTED,
    RequestStatus.APPROVED,
  ];

  if (!allowedPendingTransitions.includes(newStatus)) {
    reply.code(400).send({ error: 'PENDING requests can only be APPROVED or REJECTED' });
    return;
  }

  const authError = canRenewApproveOrRejectRequest(currentUser, existingRequest);
  if (authError) {
    reply.code(authError.code).send({ error: authError.message });
    return;
  }

  await RequestService.updateRequestStatus(existingRequest.id, newStatus);
  const updatedRequest = await RequestService.fetchExactAndShapeRequest(existingRequest.id);

  await logAudit(
    {
      userId: getUserIdFromRequest(req),
      action: AuditActionType.REQUEST_STATUS_CHANGE,
      entityType: 'Request',
      entityId: existingRequest.id,
      oldValues: { status: existingRequest.status },
      newValues: { status: newStatus },
    },
    req,
  );

  if (updatedRequest) {
    await notifyTransitionSafely(
      { log: { error: (err: unknown) => req.log.error(err) } },
      updatedRequest,
      currentUser.sub,
    );
  }

  reply.send({ request: updatedRequest });
}

async function handleApprovedStatusUpdate(
  req: FastifyRequest,
  reply: FastifyReply,
  existingRequest: NonNullable<RequestWithRelations>,
  newStatus: RequestStatusValue,
  currentUser: CurrentUser,
) {
  if (newStatus !== RequestStatus.ISSUED) {
    reply.code(400).send({ error: 'approved request can only be set to ISSUED' });
    return;
  }

  const authError = canIssueRequest(currentUser);
  if (authError) {
    reply.code(authError.code).send({ error: authError.message });
    return;
  }

  const body = req.body as UpdateStatusBody;
  const issueItems = body.issueItems;

  await RequestService.issueRequestTransaction(existingRequest, issueItems);
  const updatedRequest = await RequestService.fetchExactAndShapeRequest(existingRequest.id);
  await logAudit(
    {
      userId: getUserIdFromRequest(req),
      action: AuditActionType.REQUEST_STATUS_CHANGE,
      entityType: 'Request',
      entityId: existingRequest.id,
      oldValues: { status: existingRequest.status },
      newValues: {
        status: updatedRequest?.status ?? newStatus,
        fulfilledItems: updatedRequest?.items ?? existingRequest.requestItems,
      },
    },
    req,
  );

  if (updatedRequest) {
    await notifyTransitionSafely(
      { log: { error: (err: unknown) => req.log.error(err) } },
      updatedRequest,
      currentUser.sub,
    );
  }

  reply.send({ request: updatedRequest });
}

async function handleRequestedRenewalStatusUpdate(
  req: FastifyRequest,
  reply: FastifyReply,
  existingRequest: NonNullable<RequestWithRelations>,
  newStatus: RequestStatusValue,
  currentUser: CurrentUser,
) {
  const isApproval = newStatus === RequestStatus.RENEWED;
  const revertStatus = statusBeforeRenewal(existingRequest.requestItems);
  const isRejection =
    newStatus === RequestStatus.ISSUED || newStatus === RequestStatus.PARTIALLY_ISSUED;

  if (!isApproval && !isRejection) {
    reply.code(400).send({
      error: 'requested renewal request can only be set to RENEWED, ISSUED, or PARTIALLY_ISSUED',
    });
    return;
  }

  if (isRejection && newStatus !== revertStatus) {
    reply.code(400).send({
      error: `renewal rejection for this request must set status to ${revertStatus}`,
    });
    return;
  }

  const authError = canRenewApproveOrRejectRequest(currentUser, existingRequest);
  if (authError) {
    reply.code(authError.code).send({ error: authError.message });
    return;
  }

  if (isApproval) {
    await RequestService.approveRenewRequestTransaction(existingRequest);
  } else {
    await RequestService.rejectRenewRequestTransaction(existingRequest, revertStatus);
  }
  const updatedRequest = await RequestService.fetchExactAndShapeRequest(existingRequest.id);

  await logAudit(
    {
      userId: getUserIdFromRequest(req),
      action: AuditActionType.REQUEST_STATUS_CHANGE,
      entityType: 'Request',
      entityId: existingRequest.id,
      oldValues: { status: existingRequest.status },
      newValues: { status: newStatus },
    },
    req,
  );

  if (updatedRequest && isApproval) {
    await notifyTransitionSafely(
      { log: { error: (err: unknown) => req.log.error(err) } },
      updatedRequest,
      currentUser.sub,
    );
  } else if (updatedRequest) {
    await notifyRenewalRejected(updatedRequest, currentUser.sub);
  }

  reply.send({ request: updatedRequest });
}

async function handleIssuedStatusUpdate(
  req: FastifyRequest,
  reply: FastifyReply,
  existingRequest: NonNullable<RequestWithRelations>,
  newStatus: RequestStatusValue,
  currentUser: CurrentUser,
  lastRenewReason: string | undefined,
) {
  if (
    newStatus !== RequestStatus.RETURNED &&
    newStatus !== RequestStatus.REQUESTED_RENEW &&
    newStatus !== RequestStatus.PARTIALLY_RETURNED
  ) {
    reply.code(400).send({
      error: 'issued request can only be set to RETURNED, PARTIALLY_RETURNED, or REQUESTED_RENEW',
    });
    return;
  }
  if (newStatus === RequestStatus.RETURNED || newStatus === RequestStatus.PARTIALLY_RETURNED) {
    const authError = canIssueRequest(currentUser);
    if (authError) {
      reply.code(authError.code).send({ error: authError.message });
      return;
    }
    const body = req.body as UpdateStatusBody;
    const returnItems = body.returnItems;
    await RequestService.returnRequestTransaction(existingRequest, returnItems);
  } else if (newStatus === RequestStatus.REQUESTED_RENEW) {
    const ownerError = canRequestRenewal(currentUser, existingRequest);
    if (ownerError) {
      reply.code(ownerError.code).send({ error: ownerError.message });
      return;
    }
    await RequestService.requestForRenewalTransaction(existingRequest, lastRenewReason);
  }
  const updatedRequest = await RequestService.fetchExactAndShapeRequest(existingRequest.id);

  await logAudit(
    {
      userId: getUserIdFromRequest(req),
      action: AuditActionType.REQUEST_STATUS_CHANGE,
      entityType: 'Request',
      entityId: existingRequest.id,
      oldValues: { status: existingRequest.status },
      newValues: { status: newStatus },
    },
    req,
  );

  if (updatedRequest) {
    await notifyTransitionSafely(
      { log: { error: (err: unknown) => req.log.error(err) } },
      updatedRequest,
      currentUser.sub,
    );
  }

  reply.send({ request: updatedRequest });
}

async function handleExpiredStatusUpdate(
  req: FastifyRequest,
  reply: FastifyReply,
  existingRequest: NonNullable<RequestWithRelations>,
  newStatus: RequestStatusValue,
  currentUser: CurrentUser,
) {
  if (newStatus !== RequestStatus.RETURNED) {
    reply.code(400).send({
      error: 'EXPIRED request can only be set to RETURNED',
    });
    return;
  }

  const authError = canIssueRequest(currentUser);
  if (authError) {
    reply.code(authError.code).send({ error: authError.message });
    return;
  }

  const body = req.body as UpdateStatusBody;
  const writtenOffBefore = new Map(
    existingRequest.requestItems.map((item) => [item.componentId, item.writtenOffQuantity ?? 0]),
  );

  // preserveStatus: a late return does not undo the fact that the deadline was missed.
  await RequestService.returnRequestTransaction(existingRequest, body.returnItems, {
    preserveStatus: true,
  });
  const updatedRequest = await RequestService.fetchExactAndShapeRequest(existingRequest.id);

  await logAudit(
    {
      userId: getUserIdFromRequest(req),
      action: AuditActionType.REQUEST_STATUS_CHANGE,
      entityType: 'Request',
      entityId: existingRequest.id,
      oldValues: { status: existingRequest.status },
      newValues: { status: updatedRequest?.status ?? RequestStatus.EXPIRED },
    },
    req,
  );

  for (const item of updatedRequest?.items ?? []) {
    const before = writtenOffBefore.get(item.componentId) ?? 0;
    const after = item.writtenOffQuantity ?? 0;
    if (after >= before) continue;

    await logAudit(
      {
        userId: getUserIdFromRequest(req),
        action: AuditActionType.INVENTORY_ADJUST,
        entityType: 'Component',
        entityId: item.componentId,
        newValues: { totalQuantityDelta: before - after },
        metadata: {
          reason: 'RECLAIMED_AFTER_EXPIRY',
          requestId: existingRequest.id,
          quantity: before - after,
        },
      },
      req,
    );
  }

  if (updatedRequest) {
    await notifyTransitionSafely(
      { log: { error: (err: unknown) => req.log.error(err) } },
      { ...updatedRequest, status: RequestStatus.EXPIRED },
      currentUser.sub,
    );
  }

  reply.send({ request: updatedRequest });
}

async function handleUpdateRequestStatus(
  app: { log: { error: (err: unknown) => void } },
  req: FastifyRequest,
  reply: FastifyReply,
) {
  const params = req.params as { id?: string };
  const body = req.body as UpdateStatusBody;
  const id = params?.id;

  if (!id) {
    reply.code(400).send({ error: 'request id is required' });
    return;
  }

  const statusError = validateStatusInput(body?.status);
  if (statusError) {
    reply.code(statusError.code).send({ error: statusError.message });
    return;
  }

  const statusRaw = typeof body.status === 'string' ? body.status.trim() : '';
  if (!statusRaw) {
    reply.code(400).send({ error: 'status is required' });
    return;
  }
  const newStatus = statusRaw as RequestStatusValue;

  const currentUser = getCurrentUser(req);
  const currentUserId = currentUser?.sub;
  if (!currentUserId) {
    reply.code(401).send({ error: 'unauthorized' });
    return;
  }

  const expired = await expireOverdueRequests({ requestId: id });
  fanOutExpiredRequests(expired, (err, message) => req.log.error({ err }, message));

  const existingRequest = await RequestService.fetchRequestWithItems(id);

  if (!existingRequest) {
    reply.code(404).send({ error: 'request not found' });
    return;
  }

  switch (existingRequest.status) {
    case RequestStatus.PENDING:
      await handlePendingStatusUpdate(req, reply, existingRequest, newStatus, currentUser);
      break;

    case RequestStatus.APPROVED:
      await handleApprovedStatusUpdate(req, reply, existingRequest, newStatus, currentUser);
      break;

    case RequestStatus.ISSUED: {
      const lastRenewReason = sanitizeString(body.lastRenewReason);
      await handleIssuedStatusUpdate(
        req,
        reply,
        existingRequest,
        newStatus,
        currentUser,
        lastRenewReason,
      );
      break;
    }

    case RequestStatus.PARTIALLY_ISSUED: {
      if (newStatus === RequestStatus.ISSUED) {
        await handleApprovedStatusUpdate(req, reply, existingRequest, newStatus, currentUser);
      } else {
        const lastRenewReason = sanitizeString(body.lastRenewReason);
        await handleIssuedStatusUpdate(
          req,
          reply,
          existingRequest,
          newStatus,
          currentUser,
          lastRenewReason,
        );
      }
      break;
    }

    case RequestStatus.REQUESTED_RENEW:
      await handleRequestedRenewalStatusUpdate(req, reply, existingRequest, newStatus, currentUser);
      break;

    case RequestStatus.RENEWED:
    case RequestStatus.PARTIALLY_RETURNED: {
      const renewedLastRenewReason = sanitizeString(body.lastRenewReason);
      await handleIssuedStatusUpdate(
        req,
        reply,
        existingRequest,
        newStatus,
        currentUser,
        renewedLastRenewReason,
      );
      break;
    }

    case RequestStatus.EXPIRED:
      await handleExpiredStatusUpdate(req, reply, existingRequest, newStatus, currentUser);
      break;

    default:
      reply.code(400).send({
        error:
          'request status can only be updated when status is PENDING, APPROVED, ISSUED, PARTIALLY_ISSUED, REQUESTED_RENEW, RENEWED, PARTIALLY_RETURNED, or EXPIRED',
      });
  }
}

async function handleDeleteRequest(
  app: { log: { error: (err: unknown) => void } },
  req: FastifyRequest,
  reply: FastifyReply,
) {
  const params = req.params as { id?: string };
  const id = params?.id;

  if (!id) {
    reply.code(400).send({ error: 'request id is required' });
    return;
  }

  const currentUser = getCurrentUser(req);
  const currentUserId = currentUser?.sub;
  if (!currentUserId) {
    reply.code(401).send({ error: 'unauthorized' });
    return;
  }

  const expired = await expireOverdueRequests({ requestId: id });
  fanOutExpiredRequests(expired, (err, message) => req.log.error({ err }, message));

  const existingRequest = await RequestService.fetchRequestWithItems(id);

  if (!existingRequest) {
    reply.code(404).send({ error: 'request not found' });
    return;
  }

  const authError = canDeleteRequest(currentUser, existingRequest);
  if (authError) {
    reply.code(authError.code).send({ error: authError.message });
    return;
  }

  await RequestService.deleteRequest(id);

  await logAudit(
    {
      userId: getUserIdFromRequest(req),
      action: AuditActionType.DELETE,
      entityType: 'Request',
      entityId: id,
      oldValues: { status: existingRequest.status, userId: existingRequest.userId },
    },
    req,
  );

  reply.code(204).send();
}

function generateRequestsCsv(
  requests: Awaited<ReturnType<typeof RequestService.getRequests>>,
): string {
  const header = [
    'Request ID',
    'User Email',
    'User Name',
    'Target Faculty',
    'Project Title',
    'Status',
    'Created At',
    'Return Due At',
    'Late Fee (INR)',
    'Items Summary',
  ].join(',');

  const rows = requests.map((req) => {
    const itemsSummary = req.items
      .map((item) => {
        const parts = [`Req:${item.quantity}`];
        if ((item.fulfilledQuantity ?? 0) > 0) {
          parts.push(`Ful:${item.fulfilledQuantity}`);
        }
        if ((item.returnedQuantity ?? 0) > 0) {
          parts.push(`Ret:${item.returnedQuantity}`);
        }
        return `${item.component?.name} (${parts.join(', ')})`;
      })
      .join('; ');

    return [
      escapeCsvField(req.id),
      escapeCsvField(req.user?.email),
      escapeCsvField(req.user?.name),
      escapeCsvField(req.targetFaculty?.name || req.targetFaculty?.email),
      escapeCsvField(req.projectTitle),
      escapeCsvField(req.status),
      escapeCsvField(req.createdAt),
      escapeCsvField(req.returnDueAt),
      escapeCsvField(String(req.lateFeeAccrued ?? 0)),
      escapeCsvField(itemsSummary),
    ].join(',');
  });

  return [header, ...rows].join('\n');
}

async function handleExportRequestsCsv(
  app: { log: { error: (err: unknown) => void } },
  req: FastifyRequest,
  reply: FastifyReply,
) {
  const currentUser = getCurrentUser(req);
  const currentUserId = currentUser?.sub;
  if (!currentUserId) {
    return reply.code(401).send({ error: 'unauthorized' });
  }

  const userRole = currentUser.role;
  if (!canExportCsv(userRole)) {
    return reply.code(403).send({ error: 'forbidden: admin, LA, or faculty role required' });
  }

  try {
    const isAdmin = isAdminOrLA(userRole);
    const isFaculty = userRole === UserRole.FACULTY;

    const requests = await RequestService.getRequests({
      userId: isAdmin ? undefined : currentUserId,
      targetFacultyId: isFaculty ? currentUserId : undefined,
    });

    const csv = generateRequestsCsv(requests);

    return reply
      .header('Content-Type', 'text/csv')
      .header('Content-Disposition', 'attachment; filename="requests.csv"')
      .send(csv);
  } catch (err) {
    app.log.error(err);
    return reply.code(500).send({ error: 'failed to export requests' });
  }
}

async function handleGenerateOtp(
  _app: FastifyInstance,
  req: FastifyRequest<{ Params: { id: string } }>,
  reply: FastifyReply,
) {
  const requestId = req.params.id;
  const existing = await RequestService.fetchFullRequest(requestId);
  if (!existing) {
    reply.code(404).send({ error: 'Request not found' });
    return;
  }
  const result = RequestService.generateRequestOtp(requestId);
  reply.send(result);
}

const requestsRoutes: FastifyPluginCallback = (app, _opts, done) => {
  app.post('/requests', { preHandler: requireAuth, schema: createRequestSchema }, (req, reply) =>
    handleCreateRequest(app, req, reply),
  );

  app.get('/faculty', { preHandler: requireAuth }, (_, reply) => handleGetFaculty(app, reply));

  app.get('/requests', { preHandler: requireAuth }, (req, reply) =>
    handleGetRequests(app, req, reply),
  );

  app.get('/requests/:id', { preHandler: requireAuth }, (req, reply) =>
    handleGetRequestById(app, req as FastifyRequest<{ Params: { id: string } }>, reply),
  );

  app.post('/requests/:id/otp', { preHandler: requireAuth }, (req, reply) =>
    handleGenerateOtp(app, req as FastifyRequest<{ Params: { id: string } }>, reply),
  );

  app.get('/requests/export/csv', { preHandler: requireAuth }, (req, reply) =>
    handleExportRequestsCsv(app, req, reply),
  );

  app.put(
    '/requests/:id',
    { preHandler: requireAuth, schema: updateRequestStatusSchema },
    (req, reply) => handleUpdateRequestStatus(app, req, reply),
  );

  app.delete('/requests/:id', { preHandler: requireAuth }, (req, reply) =>
    handleDeleteRequest(app, req, reply),
  );

  app.get('/requests/events', { preHandler: requireAuth }, (req, reply) => {
    const p = pushable<EventMessage>({ objectMode: true });
    const onUpdate = () => {
      p.push({ data: 'updated' });
    };

    events.on(EventType.REQUESTS_UPDATED, onUpdate);

    req.raw.on('close', () => {
      events.off(EventType.REQUESTS_UPDATED, onUpdate);
      p.end();
    });

    reply.sse(p);
  });

  done();
};

export default requestsRoutes;
