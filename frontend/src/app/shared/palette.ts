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

/** Neutral grey: no data, not seen, no figure (always with a label). */
export const NEUTRAL_GREY = '#8a8f98';

/** A vehicle without a known delay. */
export const NO_DELAY_COLOR = NEUTRAL_GREY;

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
 * Categorical colours for a line's recorded variants (Routes, lines without a timetable), in order: the most driven
 * first; further ones repeat them. Checked with the dataviz palette validator (light surface); every variant is also
 * named in a list beside the map.
 */
export const VARIANT_COLORS = [
  '#2a78d6',
  '#eb6834',
  '#1baf7a',
  '#c2189b',
  '#8a5a00',
  '#6250d6',
  '#d7301f',
];

/**
 * Bus lanes on maps: a wide, translucent violet band under the vehicles and the line, so it reads as part of the
 * street. Not a delay, line-kind, boundary or depot colour.
 */
export const BUS_LANE_STYLE = {
  color: '#6f42c1',
  weight: 6,
  opacity: 0.45,
  label: 'Bus lane',
} as const;

/** Dark ink for outlines and dots on maps (chosen or highlighted marks, stops). */
export const INK = '#1f2933';
/** White, for outlines that separate neighbouring areas on maps. */
export const WHITE = '#ffffff';

/** Network coverage maps: the area near a stop (one see-through blue), and the woods (light green, lined). */
export const COVERAGE_STYLE = {
  covered: '#2a78d6',
  forestFill: '#9fdc8f',
  forestLine: '#4f9a45',
} as const;

/**
 * Population density (residents per km²) on the density map: steps light → dark on the sequential blue scale; `max`
 * is exclusive, null for the last step. The woods sit below the first step (nobody lives there): pale grey, lined.
 * Places without a figure are white with a dashed grey outline.
 */
export const DENSITY_SCALE: { min: number; max: number | null; color: string }[] = [
  { min: 0, max: 250, color: SEQUENTIAL_BLUE[0] },
  { min: 250, max: 1000, color: SEQUENTIAL_BLUE[1] },
  { min: 1000, max: 2500, color: SEQUENTIAL_BLUE[2] },
  { min: 2500, max: 5000, color: SEQUENTIAL_BLUE[3] },
  { min: 5000, max: 10000, color: SEQUENTIAL_BLUE[4] },
  { min: 10000, max: null, color: SEQUENTIAL_BLUE[5] },
];
export const DENSITY_NONE = { fill: '#f1f3f5', line: '#9aa1a9' } as const;
export const DENSITY_UNKNOWN = { fill: WHITE, line: NEUTRAL_GREY } as const;

/**
 * Brigades: consecutive trips alternate between two colours (validated, light surface); a part not run (status:
 * critical), not seen or without data (neutral), a change of bus (status: warning).
 */
export const TRIP_COLORS = ['#2a78d6', '#c2189b'];
export const NOT_RUN_COLOR = '#d7301f';
export const NOT_SEEN_COLOR = NEUTRAL_GREY;
export const CHANGE_COLOR = '#d48806';

/** Bus lanes by direction: one direction only, and both ways. */
export const ONE_WAY_COLOR = '#6f42c1';
export const BOTH_WAYS_COLOR = '#e8590c';

/**
 * The diverging scale for a change in delay (seconds per bus): blue where buses recover delay, red where they gain
 * it, grey around zero. Each arm is a one-hue ramp validated light → dark; the ends are far apart for colour-blind
 * readers too. Numbers are always shown beside the colour.
 */
export const DELAY_CHANGE_CLASSES: { to: number; color: string; label: string; dark: boolean }[] = [
  { to: -60, color: '#104281', label: 'recovers 1 min or more', dark: true },
  { to: -30, color: '#2a78d6', label: 'recovers 30–60 s', dark: true },
  { to: -10, color: '#86b6ef', label: 'recovers 10–30 s', dark: false },
  { to: 10, color: '#d4d3ce', label: 'within 10 s', dark: false },
  { to: 30, color: '#ec948a', label: 'gains 10–30 s', dark: false },
  { to: 60, color: '#e34948', label: 'gains 30–60 s', dark: true },
  { to: Infinity, color: '#a32626', label: 'gains 1 min or more', dark: true },
];
