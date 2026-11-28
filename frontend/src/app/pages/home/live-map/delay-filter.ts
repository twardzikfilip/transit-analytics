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

/** The kinds of route to filter by; urban takes in the night routes, which are urban too. */
export const ROUTE_TYPES: { key: RouteType; label: string; subtypes: RouteSubtype[] }[] = [
  { key: 'urban', label: 'live.types.urban', subtypes: ['urban', 'night_urban'] },
  { key: 'suburban', label: 'live.types.suburban', subtypes: ['suburban'] },
  { key: 'regional', label: 'live.types.regional', subtypes: ['regional'] },
];

export type RouteType = 'urban' | 'suburban' | 'regional';

/** Reads the kind of route from the URL; null (all routes) when unset or unknown. */
export function parseRouteType(value: string | null): RouteType | null {
  return ROUTE_TYPES.find((t) => t.key === value)?.key ?? null;
}

/** Vehicles on routes of one kind; null = all. A vehicle whose route's kind isn't known is left out by a filter. */
export function ofRouteType(positions: LivePosition[], type: RouteType | null): LivePosition[] {
  const kind = ROUTE_TYPES.find((t) => t.key === type);
  return kind
    ? positions.filter((p) => p.subtype !== null && kind.subtypes.includes(p.subtype))
    : positions;
}
