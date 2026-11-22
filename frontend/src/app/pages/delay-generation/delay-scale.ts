import { formatDelay } from '../../shared/format/time';

/** The diverging scale for a change in delay (see shared/palette.ts). */
import { DELAY_CHANGE_CLASSES } from '../../shared/palette';
import { t, tn } from '../../core/i18n/t';

export { DELAY_CHANGE_CLASSES };

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
  if (rounded === 0) return t('delayGen.onTime');
  return t(rounded > 0 ? 'delayGen.late' : 'delayGen.early', {
    delay: formatDelay(Math.abs(rounded)),
  });
}

/** Bus-minutes of delay generated per day on a stretch: average per bus × buses per day. */
export function minutesPerDay(meanSeconds: number, runs: number, days: number): number {
  return (meanSeconds * runs) / Math.max(1, days) / 60;
}
