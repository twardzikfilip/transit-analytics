import { LineStats, TimePeriod } from '../../core/models/models';
import { kmh } from '../../shared/format/number';
import { formatDelay } from '../../shared/format/time';
import { t, tn } from '../../core/i18n/t';

/** One way of ranking the lines: its label ("Longest") and which end of the scale comes first. */
export interface RankingSide {
  label: string;
  /** Highest value first. */
  descending: boolean;
}

/** A tile of rankings: one measure, ranked from either end (e.g. longest ⇄ shortest). */
export interface Ranking {
  key: string;
  title: string;
  /** What is measured, and when. */
  note: string;
  sides: [RankingSide, RankingSide];
  value: (l: LineStats) => number | null;
  format: (value: number) => string;
}

/** A ranked line: its name, colours and the value shown. */
export interface RankedLine {
  line: string;
  color: string | null;
  textColor: string | null;
  value: string;
}

export const TOP = 10;

/** "every 7.5 min", "every 25 min", "every 1 h 15 min", "every 2 h". */
export function frequency(minutes: number): string {
  const total = Math.round(minutes);
  if (total < 60)
    return t('routes.rank.everyMin', { n: minutes < 10 ? minutes.toFixed(1) : total });
  const h = Math.floor(total / 60);
  const rest = total % 60;
  return rest ? t('routes.rank.everyHMin', { h, m: rest }) : t('routes.rank.everyH', { h });
}
const delay = (s: number) => (s > 0 ? '+' : '') + formatDelay(s);

/**
 * The eight tiles under the lines: length, speed (peak and off-peak), regularity, delay and frequency (each in peak
 * and off-peak hours); each can be ranked from either end. Regularity is the peak hours' (when it matters most).
 */
export function rankings(peak: string, offPeak: string, delayDays: number): Ranking[] {
  const delayNote = t('routes.rank.delayNote', { days: delayDays });
  const peakNote = t('routes.rank.timetable', { periods: peak });
  const offPeakNote = t('routes.rank.timetable', { periods: offPeak });
  return [
    {
      key: 'length',
      title: t('routes.rank.length'),
      note: t('routes.rank.lengthNote'),
      sides: [
        { label: t('routes.rank.longest'), descending: true },
        { label: t('routes.rank.shortest'), descending: false },
      ],
      value: (l) => l.lengthKm,
      format: (v) => t('routes.rank.km', { n: v.toFixed(1) }),
    },
    {
      key: 'peak-speed',
      title: t('routes.rank.peakSpeed'),
      note: peakNote,
      sides: [
        { label: t('routes.rank.fastest'), descending: true },
        { label: t('routes.rank.slowest'), descending: false },
      ],
      value: (l) => l.peakSpeedKmh,
      format: kmh,
    },
    {
      key: 'off-peak-speed',
      title: t('routes.rank.offPeakSpeed'),
      note: offPeakNote,
      sides: [
        { label: t('routes.rank.fastest'), descending: true },
        { label: t('routes.rank.slowest'), descending: false },
      ],
      value: (l) => l.offPeakSpeedKmh,
      format: kmh,
    },
    {
      key: 'regularity',
      title: t('routes.rank.regularity'),
      note: t('routes.rank.regularityNote'),
      sides: [
        { label: t('routes.rank.mostRegular'), descending: false },
        { label: t('routes.rank.leastRegular'), descending: true },
      ],
      value: (l) => l.peakRegularityCv,
      format: (v) => t('routes.rank.cv', { n: v.toFixed(2) }),
    },
    {
      key: 'peak-delay',
      title: t('routes.rank.peakDelay'),
      note: delayNote,
      sides: [
        { label: t('routes.rank.mostDelayed'), descending: true },
        { label: t('routes.rank.leastDelayed'), descending: false },
      ],
      value: (l) => l.peakDelaySeconds,
      format: delay,
    },
    {
      key: 'peak-frequency',
      title: t('routes.rank.peakFrequency'),
      note: peakNote,
      sides: [
        { label: t('routes.rank.mostFrequent'), descending: false },
        { label: t('routes.rank.leastFrequent'), descending: true },
      ],
      value: (l) => l.peakIntervalMinutes,
      format: frequency,
    },
    {
      key: 'off-peak-delay',
      title: t('routes.rank.offPeakDelay'),
      note: delayNote,
      sides: [
        { label: t('routes.rank.mostDelayed'), descending: true },
        { label: t('routes.rank.leastDelayed'), descending: false },
      ],
      value: (l) => l.offPeakDelaySeconds,
      format: delay,
    },
    {
      key: 'off-peak-frequency',
      title: t('routes.rank.offPeakFrequency'),
      note: offPeakNote,
      sides: [
        { label: t('routes.rank.mostFrequent'), descending: false },
        { label: t('routes.rank.leastFrequent'), descending: true },
      ],
      value: (l) => l.offPeakIntervalMinutes,
      format: frequency,
    },
  ];
}

/** The top lines of a ranking from one end; lines without a value are left out. Ties keep the lines' order. */
export function ranked(lines: LineStats[], ranking: Ranking, side: RankingSide): RankedLine[] {
  return lines
    .map((l) => ({ l, v: ranking.value(l) }))
    .filter((x): x is { l: LineStats; v: number } => x.v !== null)
    .sort((a, b) => (side.descending ? b.v - a.v : a.v - b.v))
    .slice(0, TOP)
    .map(({ l, v }) => ({
      line: l.route,
      color: l.color,
      textColor: l.textColor,
      value: ranking.format(v),
    }));
}

/** "06:30–08:30, 15:00–17:00". */
export function periodsText(periods: TimePeriod[]): string {
  return periods.map((p) => `${p.from}–${p.to}`).join(', ');
}
