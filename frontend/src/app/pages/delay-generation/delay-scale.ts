import { formatDelay } from '../../shared/format/time';

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

/** The class of a change in delay: its colour, and whether text on it should be light. */
export function changeClass(seconds: number): (typeof DELAY_CHANGE_CLASSES)[number] {
  return DELAY_CHANGE_CLASSES.find((c) => seconds < c.to) ?? DELAY_CHANGE_CLASSES[6];
}

/** A change in delay with its sign: "+0:35", "−1:05", "0:00". */
export function signedChange(seconds: number): string {
  const rounded = Math.round(seconds);
  return (rounded > 0 ? '+' : '') + formatDelay(rounded);
}

/** A delay: "2:15 late", "0:40 early", "on time". */
export function delayText(seconds: number): string {
  const rounded = Math.round(seconds);
  if (rounded === 0) return 'on time';
  return `${formatDelay(Math.abs(rounded))} ${rounded > 0 ? 'late' : 'early'}`;
}

/** Bus-minutes of delay generated per day on a stretch: average per bus × buses per day. */
export function minutesPerDay(meanSeconds: number, runs: number, days: number): number {
  return (meanSeconds * runs) / Math.max(1, days) / 60;
}
