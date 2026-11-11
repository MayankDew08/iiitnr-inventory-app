export class DomainError extends Error {
  public readonly isDomainError = true;
  constructor(
    public statusCode: number,
    message: string,
    public code?: string,
  ) {
    super(message);
    this.name = this.constructor.name;
    Error.captureStackTrace(this, this.constructor);
  }
}

export class InsufficientQuantityError extends DomainError {
  constructor(componentName: string, requestedQty?: number) {
    super(
      400,
      `insufficient quantity for component "${componentName}"${
        requestedQty != null ? ` (requested ${requestedQty})` : ''
      }`,
      'INSUFFICIENT_QUANTITY',
    );
  }
}

export class NotFoundError extends DomainError {
  constructor(entity: string, id: string) {
    super(404, `${entity} not found: ${id}`, 'NOT_FOUND');
  }
}

export class ExcessReturnQuantityError extends DomainError {
  constructor(componentId: string, requestedQty: number, heldQty: number) {
    super(
      400,
      `cannot return ${requestedQty} of component "${componentId}"; only ${heldQty} currently held`,
      'EXCESS_RETURN_QUANTITY',
    );
  }
}

export class UnauthorizedError extends DomainError {
  constructor(message = 'Unauthorized') {
    super(401, message, 'UNAUTHORIZED');
  }
}

export class ForbiddenError extends DomainError {
  constructor(message = 'Forbidden') {
    super(403, message, 'FORBIDDEN');
  }
}

export class BadRequestError extends DomainError {
  constructor(message: string) {
    super(400, message, 'BAD_REQUEST');
  }
}
