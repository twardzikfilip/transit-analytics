import { t } from './i18n/t';
/** Display helpers for GTFS values. */

/**
 * "HH:MM:SS" → "HH:MM". GTFS counts past midnight on the same service day (25:10:00 = 01:10 the next day),
 * so those times are shown as the clock time with "+1".
 */
export function formatGtfsTime(time: string | null): string {
  if (!time) return '—';
  const [h, m] = time.split(':').map(Number);
  if (Number.isNaN(h) || Number.isNaN(m)) return time;
  const days = Math.floor(h / 24);
  const clock = `${String(h % 24).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
  return days > 0 ? `${clock} +${days}` : clock;
}

/** GTFS pickup_type / drop_off_type; null for a regular stop. */
export function boardingRule(type: number | null): string | null {
  switch (type) {
    case 1:
    case 2:
    case 3:
      return t(`timetable.rule.${type}`);
    default:
      return null;
  }
}
