import { randomInt } from 'node:crypto';

interface OtpEntry {
  requestId: string;
  expiresAt: number;
}

const otpStore = new Map<string, OtpEntry>();

const OTP_MAX_FAILURES = 5;
const OTP_LOCKOUT_MS = 10 * 60 * 1000;
const otpLookupFailures = new Map<string, { failures: number; blockedUntil: number }>();

export function isOtpLookupBlocked(userId: string): boolean {
  const entry = otpLookupFailures.get(userId);
  if (!entry || entry.blockedUntil === 0) return false;
  if (entry.blockedUntil > Date.now()) return true;
  otpLookupFailures.delete(userId);
  return false;
}

export function recordOtpLookupFailure(userId: string): void {
  const entry = otpLookupFailures.get(userId) ?? { failures: 0, blockedUntil: 0 };
  entry.failures += 1;
  if (entry.failures >= OTP_MAX_FAILURES) {
    entry.blockedUntil = Date.now() + OTP_LOCKOUT_MS;
    entry.failures = 0;
  }
  otpLookupFailures.set(userId, entry);
}

export function clearOtpLookupFailures(userId: string): void {
  otpLookupFailures.delete(userId);
}

export function generateRequestOtp(requestId: string): { otp: string; expiresAt: string } {
  const now = Date.now();
  for (const [code, entry] of otpStore.entries()) {
    if (entry.expiresAt < now) {
      otpStore.delete(code);
    }
  }

  for (const [code, entry] of otpStore.entries()) {
    if (entry.requestId === requestId && entry.expiresAt > now) {
      return {
        otp: code,
        expiresAt: new Date(entry.expiresAt).toISOString(),
      };
    }
  }

  let code: string;
  do {
    code = randomInt(1000, 10000).toString();
  } while (otpStore.has(code));

  const expiresAtMs = now + 10 * 60 * 1000;
  otpStore.set(code, {
    requestId,
    expiresAt: expiresAtMs,
  });

  return {
    otp: code,
    expiresAt: new Date(expiresAtMs).toISOString(),
  };
}

export function consumeOtp(otp: string): string | null {
  const entry = otpStore.get(otp.trim());
  if (!entry) return null;
  if (entry.expiresAt < Date.now()) {
    otpStore.delete(otp.trim());
    return null;
  }
  const requestId = entry.requestId;
  otpStore.delete(otp.trim());
  return requestId;
}

export function invalidateOtpForRequest(requestId: string) {
  for (const [code, entry] of otpStore.entries()) {
    if (entry.requestId === requestId) {
      otpStore.delete(code);
    }
  }
}
