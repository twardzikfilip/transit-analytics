import { DelayBand } from '../../core/models/models';

export interface DelayBandRow extends DelayBand {
  label: string;
  /** Share of all reports with a delay, 0–1. */
  share: number;
}

/** "Under 1 min (on time or early)", "1–2 min", …, "10 min or more". */
export function bandLabel(band: DelayBand): string {
  if (band.fromMinutes === null) return `Under ${band.toMinutes} min (on time or early)`;
  if (band.toMinutes === null) return `${band.fromMinutes} min or more`;
  return `${band.fromMinutes}–${band.toMinutes} min`;
}

export function bandRows(bands: DelayBand[]): DelayBandRow[] {
  const total = bands.reduce((sum, b) => sum + b.reports, 0);
  return bands.map((b) => ({ ...b, label: bandLabel(b), share: total ? b.reports / total : 0 }));
}

/** Share as a percentage with one decimal; a small but non-zero share shows as "<0.1%" rather than "0.0%". */
export function formatShare(share: number): string {
  if (share > 0 && share < 0.001) return '<0.1%';
  return `${(share * 100).toFixed(1)}%`;
}

/** Delay as "m:ss", with a minus sign when early; "—" when unknown. */
export function formatDelay(seconds: number | null): string {
  if (seconds === null) return '—';
  const sign = seconds < 0 ? '−' : '';
  const abs = Math.abs(Math.round(seconds));
  return `${sign}${Math.floor(abs / 60)}:${String(abs % 60).padStart(2, '0')}`;
}
