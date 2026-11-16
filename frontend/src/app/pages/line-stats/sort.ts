import { LineStats } from '../../core/models/models';

export type SortKey =
  | 'route'
  | 'lengthKm'
  | 'shortestTravelSeconds'
  | 'longestTravelSeconds'
  | 'peakSpeedKmh'
  | 'offPeakSpeedKmh'
  | 'peakIntervalMinutes'
  | 'offPeakIntervalMinutes'
  | 'peakVehicles'
  | 'offPeakVehicles';

export interface Sort {
  key: SortKey;
  descending: boolean;
}

/** Numbers in numeric order, then names with letters (N1, N2, …). */
export function compareLineNames(a: string, b: string): number {
  return a.localeCompare(b, undefined, { numeric: true });
}

/** Sorts lines by a column; lines without a value for it always go last, whatever the direction. */
export function sortLines(lines: LineStats[], sort: Sort): LineStats[] {
  const dir = sort.descending ? -1 : 1;
  return [...lines].sort((a, b) => {
    if (sort.key === 'route') return dir * compareLineNames(a.route, b.route);
    const x = a[sort.key];
    const y = b[sort.key];
    if (x === null && y === null) return compareLineNames(a.route, b.route);
    if (x === null) return 1;
    if (y === null) return -1;
    return dir * (x - y) || compareLineNames(a.route, b.route);
  });
}

/** Clicking the active column flips the direction; another column starts ascending. */
export function nextSort(current: Sort, key: SortKey): Sort {
  return current.key === key
    ? { key, descending: !current.descending }
    : { key, descending: false };
}

/** The first `count` lines by a value, leaving out lines without it (e.g. top 5 longest). */
export function topLines(
  lines: LineStats[],
  key: Exclude<SortKey, 'route'>,
  descending: boolean,
  count = 5,
): LineStats[] {
  return sortLines(
    lines.filter((l) => l[key] !== null),
    { key, descending },
  ).slice(0, count);
}
