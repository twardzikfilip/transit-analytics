/** Display helpers for GTFS values. */

const ROUTE_TYPES: Record<number, string> = {
  0: 'Tram',
  1: 'Metro',
  2: 'Rail',
  3: 'Bus',
  4: 'Ferry',
  5: 'Cable tram',
  6: 'Aerial lift',
  7: 'Funicular',
  11: 'Trolleybus',
  12: 'Monorail',
};

export function routeTypeLabel(type: number | null): string {
  return type === null ? '—' : (ROUTE_TYPES[type] ?? `Type ${type}`);
}

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
      return 'none';
    case 2:
      return 'phone the agency';
    case 3:
      return 'on request';
    default:
      return null;
  }
}

/** Route colours are "#RRGGBB" or null; fall back to the theme accent. */
export function routeColor(color: string | null): string | null {
  return color && /^#[0-9a-f]{6}$/i.test(color) ? color : null;
}
