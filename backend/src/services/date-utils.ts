const IST_OFFSET_MS = (5 * 60 + 30) * 60 * 1000;
export const IST_DAY_MS = 24 * 60 * 60 * 1000;

export function istDayIndex(date: Date): number {
  return Math.floor((date.getTime() + IST_OFFSET_MS) / IST_DAY_MS);
}

export function istDaysBetween(from: Date, to: Date): number {
  return istDayIndex(to) - istDayIndex(from);
}

export function istDayStart(date: Date): Date {
  return new Date(istDayIndex(date) * IST_DAY_MS - IST_OFFSET_MS);
}

export function formatIstDate(iso: string | Date): string {
  const date = typeof iso === 'string' ? new Date(iso) : iso;
  return date.toLocaleDateString('en-IN', {
    timeZone: 'Asia/Kolkata',
    weekday: 'long',
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  });
}
