import { LineRef, RouteSubtype, Segment, VariantKind } from '../../core/models/models';
import { localTime } from '../../shared/format/time';

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

/** What a variant is, in words. */
export function variantKindLabel(kind: VariantKind): string {
  return { main: 'Base route', longer: 'Longer', shorter: 'Shorter', depot: 'Depot run' }[kind];
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
