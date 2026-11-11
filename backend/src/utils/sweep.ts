export const HOURLY_SWEEP_INTERVAL_MS = 60 * 60 * 1000;

export function startIntervalSweep(
  run: () => Promise<void>,
  onError: (err: unknown) => void,
  intervalMs: number = HOURLY_SWEEP_INTERVAL_MS,
): () => void {
  const safeRun = () => {
    run().catch(onError);
  };

  safeRun();
  const interval = setInterval(safeRun, intervalMs);
  interval.unref?.();

  return () => clearInterval(interval);
}
