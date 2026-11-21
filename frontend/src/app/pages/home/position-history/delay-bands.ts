import { DelayBand } from '../../../core/models/models';
import { percent } from '../../../shared/format/number';

export interface DelayBandRow extends DelayBand {
  label: string;
  /** Share of all reports with a delay, 0–1. */
  share: number;
}

/** "On time (under 2 min late, or early)", "More than 2 min (2–5)", "More than 5 min (5–10)", "More than 10 min". */
export function bandLabel(band: DelayBand): string {
  if (band.fromMinutes === null) return `On time (under ${band.toMinutes} min late, or early)`;
  if (band.toMinutes === null) return `More than ${band.fromMinutes} min`;
  return `More than ${band.fromMinutes} min (${band.fromMinutes}–${band.toMinutes})`;
}

export function bandRows(bands: DelayBand[]): DelayBandRow[] {
  const total = bands.reduce((sum, b) => sum + b.reports, 0);
  return bands.map((b) => ({ ...b, label: bandLabel(b), share: total ? b.reports / total : 0 }));
}

/** Share as a percentage with one decimal; a small but non-zero share shows as "<0.1%" rather than "0.0%". */
export function formatShare(share: number): string {
  return percent(share, { tiny: true });
}

/**
 * The window a result covers, e.g. "Mon 16 Nov, 08:36 – 14:36" or "Mon 9 Nov, 14:14 – Mon 16 Nov, 14:14" (local
 * time). `to` is exclusive, so a window ending at midnight shows 00:00 of the next day.
 */
export function windowLabel(from: string, to: string, locale?: string): string {
  const a = new Date(from);
  const b = new Date(to);
  const day = new Intl.DateTimeFormat(locale, { weekday: 'short', day: 'numeric', month: 'short' });
  const time = new Intl.DateTimeFormat(locale, {
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  });
  const start = `${day.format(a)}, ${time.format(a)}`;
  return a.toDateString() === b.toDateString()
    ? `${start} – ${time.format(b)}`
    : `${start} – ${day.format(b)}, ${time.format(b)}`;
}
