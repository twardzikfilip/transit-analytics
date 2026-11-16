/** The time window of the position history page, from the URL. */

export const PRESETS = [
  { key: '1m', label: '1 min', ms: 60_000 },
  { key: '5m', label: '5 min', ms: 5 * 60_000 },
  { key: '15m', label: '15 min', ms: 15 * 60_000 },
  { key: '30m', label: '30 min', ms: 30 * 60_000 },
  { key: '1h', label: '1 h', ms: 3_600_000 },
  { key: '3h', label: '3 h', ms: 3 * 3_600_000 },
  { key: '6h', label: '6 h', ms: 6 * 3_600_000 },
  { key: '24h', label: '24 h', ms: 24 * 3_600_000 },
  { key: '7d', label: '7 days', ms: 7 * 86_400_000 },
  { key: '30d', label: '30 days', ms: 30 * 86_400_000 },
] as const;

export const DEFAULT_PRESET = '24h';

/** Positions are recorded every 20 seconds; windows up to an hour are shown poll by poll and move with each poll. */
export const POLL_MS = 20_000;
const SHORT_WINDOW_MS = 3_600_000;

/** How often a preset window moves forward (and the page reloads): each poll for short windows, else each minute. */
export function refreshMs(window: TimeWindow): number {
  return window.to.getTime() - window.from.getTime() <= SHORT_WINDOW_MS ? POLL_MS : 60_000;
}

export interface TimeWindow {
  from: Date;
  to: Date;
  /** Preset key, or null for a custom from/to. */
  preset: string | null;
}

/**
 * `?from=2026-11-16T05:00&to=2026-11-16T17:00` (local time, as a datetime-local input gives it) is a custom window;
 * otherwise `?range=6h` or the default preset, ending at the next full minute so it includes the latest recording (for
 * windows of up to an hour, at the next poll, every 20 seconds).
 */
export function resolveWindow(
  params: { range: string | null; from: string | null; to: string | null },
  now: Date,
): TimeWindow {
  const from = params.from ? new Date(params.from) : null;
  const to = params.to ? new Date(params.to) : null;
  if (from && to && !isNaN(from.getTime()) && !isNaN(to.getTime()) && from < to) {
    return { from, to, preset: null };
  }
  const preset =
    PRESETS.find((p) => p.key === params.range) ?? PRESETS.find((p) => p.key === DEFAULT_PRESET)!;
  const step = preset.ms <= SHORT_WINDOW_MS ? POLL_MS : 60_000;
  const end = new Date(Math.floor(now.getTime() / step) * step + step);
  return { from: new Date(end.getTime() - preset.ms), to: end, preset: preset.key };
}
