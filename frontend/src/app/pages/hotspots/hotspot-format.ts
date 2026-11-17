import { Hotspot, HotspotApproach, HotspotLineTime } from '../../core/models/models';
import { formatDelay } from '../../shared/format/time';

/** "45 min" under an hour, else "2.4 h". */
export function lostTime(hours: number): string {
  return hours < 1 ? `${Math.round(hours * 60)} min` : `${hours.toFixed(1)} h`;
}

/** A wait: "40 s" under a minute, else "1:20 min". */
export function waitText(seconds: number): string {
  return seconds < 60 ? `${seconds} s` : `${formatDelay(seconds)} min`;
}

/** "70 m from Częstochowska/Piłsudskiego", to tell where a hotspot is. */
export function nearText(h: Hotspot): string {
  return h.nearestStop ? `${h.nearestStopMeters} m from ${h.nearestStop}` : 'no stop nearby';
}

const ARROWS: Record<string, { arrow: string; name: string }> = {
  N: { arrow: '↑', name: 'north' },
  NE: { arrow: '↗', name: 'north-east' },
  E: { arrow: '→', name: 'east' },
  SE: { arrow: '↘', name: 'south-east' },
  S: { arrow: '↓', name: 'south' },
  SW: { arrow: '↙', name: 'south-west' },
  W: { arrow: '←', name: 'west' },
  NW: { arrow: '↖', name: 'north-west' },
};

/** An approach as an arrow with its share ("→ 81%"), and in words for the tooltip and screen readers. */
export function approachText(a: HotspotApproach): { short: string; long: string } {
  const h = ARROWS[a.heading] ?? { arrow: a.heading, name: a.heading };
  const pct = Math.round(a.share * 100);
  return { short: `${h.arrow} ${pct}%`, long: `${pct}% heading ${h.name}` };
}

/** The lines with the most time lost there, e.g. "21, 9, 12 +3". */
export function linesText(lines: HotspotLineTime[], shown = 4): string {
  const names = lines.slice(0, shown).map((l) => l.line);
  return names.join(', ') + (lines.length > shown ? ` +${lines.length - shown}` : '');
}

/** "07–08": the hour from 07:00 to 08:00. */
export function hourText(hour: number): string {
  const h = (n: number) => String(n % 24).padStart(2, '0');
  return `${h(hour)}–${h(hour + 1)}`;
}
