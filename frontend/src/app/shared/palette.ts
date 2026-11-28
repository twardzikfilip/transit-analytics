import { RouteSubtype } from '../core/models/models';

/**
 * Labels here are translation keys (shown with t()).
 *
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
  { seconds: 60, color: '#2e9e5b', label: 'palette.onTime' },
  { seconds: 180, color: '#f2c12e', label: 'palette.min3' },
  { seconds: 360, color: '#f07f22', label: 'palette.min6' },
  { seconds: 600, color: '#d7301f', label: 'palette.min10' },
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
 * Sequential blue, light → dark (reference palette steps 250–700; the lightest still stands out on a grey map),
 * for magnitudes such as buses per stretch.
 */
export const SEQUENTIAL_BLUE = ['#86b6ef', '#5598e7', '#2a78d6', '#1c5cab', '#104281', '#0d366b'];

/**
 * City boundaries on maps: both 1 px in Bootstrap's $dark, the city limits dashed and the centre (Osiedle Centrum)
 * solid. Drawn under the vehicles and named in the map legend.
 */
export const BOUNDARY_STYLES = {
  city: { color: '#212529', dashArray: '6 4', weight: 1, label: 'palette.cityLimits' },
  centre: { color: '#212529', dashArray: undefined, weight: 1, label: 'palette.cityCentre' },
} as const;

/**
 * Categorical colours for a route's recorded variants (Routes, routes without a timetable), in order: the most driven
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
 * Bus lanes on maps: a wide, translucent band in the primary colour (--primary in styles.scss) under the vehicles
 * and the route, so it reads as part of the street.
 */
export const BUS_LANE_STYLE = {
  color: '#00a9bc',
  weight: 6,
  opacity: 0.45,
  label: 'palette.busLane',
} as const;

/** Railways on the City rail map, under the routes: railway lines, and thinner the spurs to depots and factories. */
export const RAILWAY_STYLES = {
  line: { color: '#495057', weight: 2, opacity: 0.8 },
  spur: { color: '#495057', weight: 1, opacity: 0.7 },
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
  /** The Biała valley: light blue, lined like the woods. */
  valleyFill: '#b9e3f8',
  valleyLine: '#2b8cc4',
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
 * Each kind of route's colour (badges, maps), kept in step with the --line-* tokens in styles.scss: a token in styles.scss (light and dark), and its value before styles load. */
export const ROUTE_KIND_COLORS: Record<RouteSubtype, { token: string; fallback: string }> = {
  urban: { token: '--line-urban', fallback: '#4b0082' },
  suburban: { token: '--line-suburban', fallback: '#03bf03' },
  regional: { token: '--line-regional', fallback: '#0021ff' },
  night_urban: { token: '--line-night', fallback: '#111111' },
};

/** Route numbers on a route kind's colour. */
export const ROUTE_TEXT_COLOR = '#fff';

/** BKM's colours, as $green_bkm and $lightblue_bkm (--green-bkm, --lightblue-bkm) in styles.scss. */
export const GREEN_BKM = '#4cb034';
export const LIGHTBLUE_BKM = '#2393ca';

/**
 * Brigades: consecutive trips alternate between BKM's light blue and green; a part not run (status: critical), not
 * seen or without data (neutral), a change of bus (status: warning).
 */
export const TRIP_COLORS = [LIGHTBLUE_BKM, GREEN_BKM];
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
  { to: -60, color: '#104281', label: 'palette.recovers60', dark: true },
  { to: -30, color: '#2a78d6', label: 'palette.recovers30', dark: true },
  { to: -10, color: '#86b6ef', label: 'palette.recovers10', dark: false },
  { to: 10, color: '#d4d3ce', label: 'palette.within10', dark: false },
  { to: 30, color: '#ec948a', label: 'palette.gains10', dark: false },
  { to: 60, color: '#e34948', label: 'palette.gains30', dark: true },
  { to: Infinity, color: '#a32626', label: 'palette.gains60', dark: true },
];
