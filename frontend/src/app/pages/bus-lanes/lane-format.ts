import { LaneFigures, LaneHeading } from '../../core/models/models';
import { SEQUENTIAL_BLUE } from '../../shared/palette';
import { kmh } from '../../shared/format/number';
import { t } from '../../core/i18n/t';

/** Speed classes on the map, km/h: the slowest darkest (most held up). */
export const SPEED_SCALE: { below: number; color: string; label: string }[] = [
  { below: 12, color: SEQUENTIAL_BLUE[5], label: 'busLanes.speed.under12' },
  { below: 15, color: SEQUENTIAL_BLUE[4], label: '12–15' },
  { below: 18, color: SEQUENTIAL_BLUE[3], label: '15–18' },
  { below: 21, color: SEQUENTIAL_BLUE[2], label: '18–21' },
  { below: 25, color: SEQUENTIAL_BLUE[1], label: '21–25' },
  { below: Infinity, color: SEQUENTIAL_BLUE[0], label: '25+' },
];

/** A stretch without recordings in the window. */
export const NO_SPEED_COLOR = '#8a8f98';

export function speedColor(speed: number | null | undefined): string {
  if (speed === null || speed === undefined) return NO_SPEED_COLOR;
  return SPEED_SCALE.find((s) => speed < s.below)!.color;
}

const ARROWS: Record<LaneHeading, string> = {
  northbound: '↑',
  eastbound: '→',
  southbound: '↓',
  westbound: '←',
};

/** "→ eastbound". */
export function headingText(heading: LaneHeading): string {
  return `${ARROWS[heading]} ${t(`busLanes.heading.${heading}`)}`;
}

/** "17.6 km/h", or "–". */
export function speedText(speed: number | null | undefined): string {
  return speed === null || speed === undefined ? '–' : kmh(speed);
}

/** "+17.6 s/km" (delay gained), "−4.0 s/km" (made up), or "–". */
export function delayText(perKm: number | null | undefined): string {
  if (perKm === null || perKm === undefined) return '–';
  const sign = perKm > 0 ? '+' : perKm < 0 ? '−' : '';
  return t('busLanes.perKm', { sign, n: Math.abs(perKm).toFixed(1) });
}

/** "7% slower", "3% faster", or "–". */
export function slowdownText(f: LaneFigures | null | undefined): string {
  const s = f?.peakSlowdown;
  if (s === null || s === undefined) return '–';
  if (Math.abs(s) < 0.5) return t('busLanes.noSlower');
  return s > 0
    ? t('busLanes.slower', { n: s.toFixed(0) })
    : t('busLanes.faster', { n: (-s).toFixed(0) });
}

/** Lines as text, the first few then "+n". */
export function linesText(lines: string[], first = 8): string {
  return lines.length <= first
    ? lines.join(', ')
    : `${lines.slice(0, first).join(', ')} +${lines.length - first}`;
}
