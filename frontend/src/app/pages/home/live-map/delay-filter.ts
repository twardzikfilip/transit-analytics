import { LivePosition, RouteSubtype } from '../../../core/models/models';

/** Reads the "minimum delay in minutes" filter from the URL; null when unset or not a number ≥ 0. */
export function parseMinDelay(value: string | null): number | null {
  if (value === null || value.trim() === '') return null;
  const minutes = Number(value);
  return Number.isFinite(minutes) && minutes >= 0 ? minutes : null;
}

/**
 * Vehicles running more than `minMinutes` late. With no filter, all vehicles; with a filter, vehicles whose delay
 * is unknown are left out.
 */
export function delayedOver(positions: LivePosition[], minMinutes: number | null): LivePosition[] {
  if (minMinutes === null) return positions;
  const threshold = minMinutes * 60;
  return positions.filter((p) => p.delaySeconds !== null && p.delaySeconds > threshold);
}

/** Vehicles of one operator; '' = all. */
export function ofAgency(positions: LivePosition[], agency: string): LivePosition[] {
  return agency ? positions.filter((p) => p.agency === agency) : positions;
}

/** Operators of the vehicles, sorted, plus `selected` (when set) so a chosen operator stays in the list. */
export function agencies(positions: LivePosition[], selected = ''): string[] {
  const names = new Set(positions.map((p) => p.agency).filter((a): a is string => !!a));
  if (selected) names.add(selected);
  return [...names].sort((a, b) => a.localeCompare(b));
}

/** The kinds of line to filter by; urban takes in the night lines, which are urban too. */
export const LINE_TYPES: { key: LineType; label: string; subtypes: RouteSubtype[] }[] = [
  { key: 'urban', label: 'live.types.urban', subtypes: ['urban', 'night_urban'] },
  { key: 'suburban', label: 'live.types.suburban', subtypes: ['suburban'] },
  { key: 'regional', label: 'live.types.regional', subtypes: ['regional'] },
];

export type LineType = 'urban' | 'suburban' | 'regional';

/** Reads the kind of line from the URL; null (all lines) when unset or unknown. */
export function parseLineType(value: string | null): LineType | null {
  return LINE_TYPES.find((t) => t.key === value)?.key ?? null;
}

/** Vehicles on lines of one kind; null = all. A vehicle whose line's kind isn't known is left out by a filter. */
export function ofLineType(positions: LivePosition[], type: LineType | null): LivePosition[] {
  const kind = LINE_TYPES.find((t) => t.key === type);
  return kind
    ? positions.filter((p) => p.subtype !== null && kind.subtypes.includes(p.subtype))
    : positions;
}
