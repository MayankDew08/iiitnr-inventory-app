import type { FastifyReply } from 'fastify';

export const SESSION_COOKIE_NAME = 'iiitnr_session';

export const AUTH_TOKEN_TTL_SECONDS = 7 * 24 * 60 * 60;

function isSecureDeployment(): boolean {
  return process.env.NODE_ENV === 'production';
}

function sessionCookieOptions() {
  return {
    httpOnly: true,
    sameSite: 'strict' as const,
    secure: isSecureDeployment(),
    path: '/',
    maxAge: AUTH_TOKEN_TTL_SECONDS,
  };
}

export function setSessionCookie(reply: FastifyReply, token: string): void {
  reply.setCookie(SESSION_COOKIE_NAME, token, sessionCookieOptions());
}

export function clearSessionCookie(reply: FastifyReply): void {
  const { httpOnly, sameSite, secure, path } = sessionCookieOptions();
  reply.clearCookie(SESSION_COOKIE_NAME, { httpOnly, sameSite, secure, path });
}
