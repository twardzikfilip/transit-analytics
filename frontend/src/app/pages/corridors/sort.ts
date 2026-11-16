import { Corridor } from '../../core/models/models';

export type CorridorSortKey =
  | 'from'
  | 'stops'
  | 'km'
  | 'buses'
  | 'peakIntervalMin'
  | 'offPeakIntervalMin'
  | 'peakCv'
  | 'offPeakCv';

export interface CorridorSort {
  key: CorridorSortKey;
  descending: boolean;
}

/** The page's default: most buses first. */
export const DEFAULT_SORT: CorridorSort = { key: 'buses', descending: true };

function value(c: Corridor, key: CorridorSortKey): string | number | null {
  return key === 'stops' ? c.stops.length : c[key];
}

/** Sorts corridors by a column; ones without a value for it always go last. Ties: most buses, then by name. */
export function sortCorridors(corridors: Corridor[], sort: CorridorSort): Corridor[] {
  const dir = sort.descending ? -1 : 1;
  const tie = (a: Corridor, b: Corridor) => b.buses - a.buses || a.from.localeCompare(b.from);
  return [...corridors].sort((a, b) => {
    const x = value(a, sort.key);
    const y = value(b, sort.key);
    if (x === null && y === null) return tie(a, b);
    if (x === null) return 1;
    if (y === null) return -1;
    const cmp = typeof x === 'string' ? x.localeCompare(y as string) : x - (y as number);
    return dir * cmp || tie(a, b);
  });
}

/** Clicking the active column flips it; another column starts with its natural direction (counts high first). */
export function nextCorridorSort(current: CorridorSort, key: CorridorSortKey): CorridorSort {
  if (current.key === key) return { key, descending: !current.descending };
  return { key, descending: key === 'buses' || key === 'stops' || key === 'km' };
}
