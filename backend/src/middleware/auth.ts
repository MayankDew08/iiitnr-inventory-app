import type { FastifyRequest } from 'fastify';
import { UserRole } from '../utils/enums.js';
import type { UserRoleValue } from '../utils/enums.js';
import { ForbiddenError } from '../utils/errors.js';

export const requireAuth = async (request: FastifyRequest) => {
  await request.jwtVerify();
  const userRole = (request.user as { role?: UserRoleValue })?.role;
  if (userRole === UserRole.PENDING) {
    throw new ForbiddenError('forbidden: pending user must be verified');
  }
};

export const requireAdminOrLA = async (request: FastifyRequest) => {
  await request.jwtVerify();
  const userRole = (request.user as { role?: UserRoleValue })?.role;
  if (userRole !== UserRole.ADMIN && userRole !== UserRole.LA) {
    throw new ForbiddenError('forbidden: admin or LA role required');
  }
};

export const isAdminOrLA = (role?: UserRoleValue): boolean =>
  role === UserRole.ADMIN || role === UserRole.LA;

export const requireAdmin = async (request: FastifyRequest) => {
  await request.jwtVerify();
  const userRole = (request.user as { role?: UserRoleValue })?.role;
  if (userRole !== UserRole.ADMIN) {
    throw new ForbiddenError('forbidden: admin role required');
  }
};
