/** Sorting of the position reports table; applied by the backend, since the reports are paged there. */

export type ReportSortKey = 'time' | 'vehicle' | 'line' | 'duty' | 'towards' | 'delay' | 'nextStop';

export interface ReportSort {
  key: ReportSortKey;
  descending: boolean;
}

export const DEFAULT_REPORT_SORT: ReportSort = { key: 'time', descending: true };

const KEYS: ReportSortKey[] = ['time', 'vehicle', 'line', 'duty', 'towards', 'delay', 'nextStop'];

/** "delay,desc" (the URL and API form) → sort; anything else → newest first. */
export function parseReportSort(value: string | null): ReportSort {
  const [key, dir] = (value ?? '').split(',');
  return KEYS.includes(key as ReportSortKey)
    ? { key: key as ReportSortKey, descending: dir === 'desc' }
    : DEFAULT_REPORT_SORT;
}

export function formatReportSort(sort: ReportSort): string {
  return `${sort.key},${sort.descending ? 'desc' : 'asc'}`;
}

/** The active column flips; a new column starts newest / most delayed first for time and delay, A–Z otherwise. */
export function nextReportSort(current: ReportSort, key: ReportSortKey): ReportSort {
  if (current.key === key) return { key, descending: !current.descending };
  return { key, descending: key === 'time' || key === 'delay' };
}
