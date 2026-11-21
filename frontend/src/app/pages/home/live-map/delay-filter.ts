import { LivePosition } from '../../../core/models/models';

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
