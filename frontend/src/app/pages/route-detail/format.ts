import { LineRef, RouteSubtype, Segment, VariantKind } from '../../core/models/models';

/** Subtypes in display order, with their label and line colour (a CSS custom property from styles.scss). */
export const SUBTYPES: { subtype: RouteSubtype; label: string; color: string }[] = [
  { subtype: 'urban', label: 'Urban', color: '--line-urban' },
  { subtype: 'suburban', label: 'Suburban', color: '--line-suburban' },
  { subtype: 'night_urban', label: 'Night', color: '--line-night' },
  { subtype: 'regional', label: 'Regional', color: '--line-regional' },
];

export function subtypeLabel(subtype: RouteSubtype): string {
  return SUBTYPES.find((s) => s.subtype === subtype)?.label ?? subtype;
}

/** Lines grouped by subtype, in display order; empty groups are left out. Lines keep their (numeric) order. */
export function groupLines(lines: LineRef[]): { label: string; color: string; lines: LineRef[] }[] {
  return SUBTYPES.map((s) => ({
    label: s.label,
    color: s.color,
    lines: lines.filter((l) => l.subtype === s.subtype),
  })).filter((g) => g.lines.length > 0);
}

/** Clock time of seconds after midnight; times after midnight wrap (24:14 → "00:14"). */
export function clock(seconds: number): string {
  const minutes = Math.round(seconds / 60);
  const h = Math.floor(minutes / 60) % 24;
  return `${String(h).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
}

/** A duration in minutes: "45 min", or "4 h 04" from an hour up. */
export function duration(minutes: number): string {
  const m = Math.round(minutes);
  return m < 60 ? `${m} min` : `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, '0')}`;
}

/** Indexes of the `count` slowest segments with a measured speed, slowest first. */
export function slowestSegments(segments: Segment[], count = 3): number[] {
  return segments
    .map((s, i) => ({ i, kmh: s.kmh }))
    .filter((s): s is { i: number; kmh: number } => s.kmh !== null)
    .sort((a, b) => a.kmh - b.kmh || a.i - b.i)
    .slice(0, count)
    .map((s) => s.i);
}

/** Whole hours around the given seconds-after-midnight times, with an hour of room on each side for labels. */
export function hourRange(times: number[]): { from: number; to: number } {
  if (!times.length) return { from: 4, to: 24 };
  return {
    from: Math.max(0, Math.floor(Math.min(...times) / 3600) - 1),
    to: Math.ceil(Math.max(...times) / 3600) + 1,
  };
}

/** Round tick step (hours) so that at most `maxTicks` ticks fit. */
export function hourTickStep(span: number, maxTicks: number): number {
  return [1, 2, 3, 4, 6, 12].find((s) => span / s <= maxTicks) ?? 24;
}

/** What a variant is, in words. */
export function variantKindLabel(kind: VariantKind): string {
  return { main: 'Base route', longer: 'Longer', shorter: 'Shorter', depot: 'Depot run' }[kind];
}

const LOCAL_TIME = new Intl.DateTimeFormat('en-GB', {
  hour: '2-digit',
  minute: '2-digit',
  timeZone: 'Europe/Warsaw',
});

/** Clock time in Białystok of an instant, e.g. "17:19". */
export function localTime(iso: string): string {
  return LOCAL_TIME.format(new Date(iso));
}

/**
 * The buses of one brigade's day in order, e.g. "224 until 17:19 → 221 17:26–19:44 → 036 from 19:50": the first
 * bus until it was replaced, the last from when it took over, those in between with both times.
 */
export function spellsText(spells: { number: string; from: string; to: string }[]): string {
  return spells
    .map((s, i) => {
      if (i === 0) return `${s.number} until ${localTime(s.to)}`;
      if (i === spells.length - 1) return `${s.number} from ${localTime(s.from)}`;
      return `${s.number} ${localTime(s.from)}–${localTime(s.to)}`;
    })
    .join(' → ');
}
