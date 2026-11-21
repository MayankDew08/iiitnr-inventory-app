export const LATE_FEE_PER_DAY = 1;

export const LATE_FEE_CAP_DAYS = 4 * 365;
export const LATE_FEE_CAP = LATE_FEE_PER_DAY * LATE_FEE_CAP_DAYS;

export function computeLateFee(daysOverdue: number): number {
  if (daysOverdue <= 0) return 0;
  return Math.min(daysOverdue, LATE_FEE_CAP_DAYS) * LATE_FEE_PER_DAY;
}
