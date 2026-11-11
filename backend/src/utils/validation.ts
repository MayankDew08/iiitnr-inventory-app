import type { FastifyRequest } from 'fastify';
import { filterXSS } from 'xss';

export interface ValidationError {
  code: number;
  message: string;
}

export function isValidationError(value: unknown): value is ValidationError {
  return typeof value === 'object' && value !== null && 'code' in value && 'message' in value;
}

export function isAcceptableImageUrl(urlString: string): boolean {
  if (urlString.startsWith('/api/uploads/')) return true;
  try {
    const url = new URL(urlString);
    return url.protocol === 'http:' || url.protocol === 'https:';
  } catch {
    return false;
  }
}

export function sanitizeString(input: string | null | undefined): string | undefined {
  if (input == null) return undefined;
  return filterXSS(input.trim().slice(0, 2000));
}

export function getClientInfo(request: FastifyRequest): { ip: string; userAgent?: string } {
  return {
    ip: request.ip,
    userAgent: request.headers['user-agent'],
  };
}
