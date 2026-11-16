import { niceTicks } from '../../shared/time-series-chart/chart-geometry';

/** Line width in px for the busiest stretch; the others are thinner in proportion. */
export const MAX_WIDTH = 14;
const MIN_WIDTH = 1;

/** Width in proportion to the buses: 0 buses → 1 px, the busiest → {@link MAX_WIDTH}. */
export function widthFor(buses: number, max: number): number {
  if (max <= 0) return MIN_WIDTH;
  return MIN_WIDTH + (MAX_WIDTH - MIN_WIDTH) * (buses / max);
}

/** Sequential blue, light → dark (palette steps 250–700; the lightest still stands out on a grey map). */
const RAMP = ['#86b6ef', '#5598e7', '#2a78d6', '#1c5cab', '#104281', '#0d366b'];

export interface ColorClass {
  from: number;
  to: number;
  color: string;
}

/**
 * Equal-width classes on round numbers from 0 to the busiest stretch (e.g. 0–50, 50–100, …), so the colour steps are
 * in proportion like the widths; each gets a darker blue.
 */
export function colorClasses(max: number): ColorClass[] {
  const ticks = niceTicks(Math.max(1, max), 5);
  const classes: ColorClass[] = [];
  for (let i = 1; i < ticks.length && ticks[i - 1] < Math.max(1, max); i++) {
    classes.push({ from: ticks[i - 1], to: ticks[i], color: '' });
  }
  return classes.map((c, i) => ({
    ...c,
    color: RAMP[Math.round((i * (RAMP.length - 1)) / Math.max(1, classes.length - 1))],
  }));
}

/** The class a number of buses falls in (the top class includes its upper end). */
export function colorFor(buses: number, classes: ColorClass[]): string {
  const c = classes.find((k) => buses < k.to) ?? classes[classes.length - 1];
  return c.color;
}
