/** The time window of the position history page, from the URL. */

export const PRESETS = [
  { key: '1h', label: '1 h', ms: 3_600_000 },
  { key: '6h', label: '6 h', ms: 6 * 3_600_000 },
  { key: '24h', label: '24 h', ms: 24 * 3_600_000 },
  { key: '7d', label: '7 days', ms: 7 * 86_400_000 },
  { key: '30d', label: '30 days', ms: 30 * 86_400_000 },
] as const;

export const DEFAULT_PRESET = '24h';

export interface TimeWindow {
  from: Date;
  to: Date;
  /** Preset key, or null for a custom from/to. */
  preset: string | null;
}

/**
 * `?from=2026-11-16T05:00&to=2026-11-16T17:00` (local time, as a datetime-local input gives it) is a custom window;
 * otherwise `?range=6h` or the default preset, ending at the next full minute so it includes the latest recording.
 */
export function resolveWindow(params: { range: string | null; from: string | null; to: string | null }, now: Date): TimeWindow {
  const from = params.from ? new Date(params.from) : null;
  const to = params.to ? new Date(params.to) : null;
  if (from && to && !isNaN(from.getTime()) && !isNaN(to.getTime()) && from < to) {
    return { from, to, preset: null };
  }
  const preset = PRESETS.find((p) => p.key === params.range) ?? PRESETS.find((p) => p.key === DEFAULT_PRESET)!;
  const end = new Date(Math.floor(now.getTime() / 60_000) * 60_000 + 60_000);
  return { from: new Date(end.getTime() - preset.ms), to: end, preset: preset.key };
}

/** Value for a datetime-local input, in local time ("2026-11-16T14:05"). */
export function toLocalInput(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
