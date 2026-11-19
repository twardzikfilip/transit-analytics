import { RouteSubtype } from '../core/models/models';

/**
 * Colours used in code (map layers can't use CSS custom properties). Theme colours are read from the tokens in
 * styles.scss with cssColor(), so they follow light and dark mode; the data scales below are fixed.
 */

/** A colour token from styles.scss (e.g. '--depot'), or the fallback before styles load or outside a browser. */
export function cssColor(token: string, fallback: string): string {
  if (typeof document === 'undefined') return fallback;
  return getComputedStyle(document.documentElement).getPropertyValue(token).trim() || fallback;
}

/** Delay scale (Delays map): on time (≤ 1 min late, or early) green → 3 min yellow → 6 min orange → 10+ min red. */
export const DELAY_STOPS: { seconds: number; color: string; label: string }[] = [
  { seconds: 60, color: '#2e9e5b', label: 'on time' },
  { seconds: 180, color: '#f2c12e', label: '3 min' },
  { seconds: 360, color: '#f07f22', label: '6 min' },
  { seconds: 600, color: '#d7301f', label: '10+ min' },
];

/** A vehicle without a known delay. */
export const NO_DELAY_COLOR = '#8a8f98';

/** The marker colour for a delay, blended between the stops. */
export function delayColor(seconds: number | null): string {
  if (seconds === null) return NO_DELAY_COLOR;
  if (seconds <= DELAY_STOPS[0].seconds) return DELAY_STOPS[0].color;
  for (let i = 1; i < DELAY_STOPS.length; i++) {
    const a = DELAY_STOPS[i - 1];
    const b = DELAY_STOPS[i];
    if (seconds <= b.seconds)
      return mix(a.color, b.color, (seconds - a.seconds) / (b.seconds - a.seconds));
  }
  return DELAY_STOPS[DELAY_STOPS.length - 1].color;
}

function mix(from: string, to: string, k: number): string {
  const a = hex(from);
  const b = hex(to);
  return (
    '#' +
    a
      .map((c, i) =>
        Math.round(c + (b[i] - c) * k)
          .toString(16)
          .padStart(2, '0'),
      )
      .join('')
  );
}

function hex(color: string): number[] {
  return [1, 3, 5].map((i) => parseInt(color.slice(i, i + 2), 16));
}

/**
 * Vehicle outline by kind of line (Live map): urban blue, suburban black, regional (4xx) magenta. Night lines are
 * urban lines and share their colour.
 */
export const SUBTYPE_OUTLINES: { subtype: RouteSubtype; color: string; label: string }[] = [
  { subtype: 'urban', color: '#1c5cab', label: 'Urban' },
  { subtype: 'suburban', color: '#111111', label: 'Suburban' },
  { subtype: 'regional', color: '#c2189b', label: 'Regional (4xx)' },
];

export function subtypeOutline(subtype: RouteSubtype | null): string {
  const kind = subtype === 'night_urban' ? 'urban' : subtype;
  return SUBTYPE_OUTLINES.find((o) => o.subtype === kind)?.color ?? NO_DELAY_COLOR;
}

/**
 * Sequential blue, light → dark (reference palette steps 250–700; the lightest still stands out on a grey map),
 * for magnitudes such as buses per stretch.
 */
export const SEQUENTIAL_BLUE = ['#86b6ef', '#5598e7', '#2a78d6', '#1c5cab', '#104281', '#0d366b'];

/**
 * City boundaries on maps: the city limits teal and dashed, the centre (Osiedle Centrum) brown and solid. Neither is
 * the purple the OpenStreetMap tiles use for administrative borders, nor a delay or line-kind colour. Drawn under
 * the vehicles and named in the map legend.
 */
export const BOUNDARY_STYLES = {
  city: { color: '#0b7285', dashArray: '6 4', label: 'City limits' },
  centre: { color: '#8a4b08', dashArray: undefined, label: 'City centre' },
} as const;

/**
 * Bus lanes on maps: a wide, translucent violet band under the vehicles and the line, so it reads as part of the
 * street. Not a delay, line-kind, boundary or depot colour.
 */
export const BUS_LANE_STYLE = { color: '#6f42c1', weight: 6, opacity: 0.45, label: 'Bus lane' } as const;
