import { Breakdown, FleetVehicle, VehicleDetail, VehicleUsage } from '../../core/models/models';
import { Bar } from '../../shared/bar-chart/bar-chart';
import { clock } from '../../shared/format/time';

const DAY_MS = 86_400_000;

/** "230 · Solaris Urbino 12". */
export function vehicleName(v: FleetVehicle): string {
  return v.model ? `${v.number} · ${v.model}` : v.number;
}

/**
 * Buses matching a search by number or model, best first: numbers starting with it, then numbers containing it, then
 * models containing it. Empty for a blank search.
 */
export function searchVehicles(usage: VehicleUsage[], query: string, limit = 8): VehicleUsage[] {
  const q = query.trim().toLowerCase();
  if (!q) return [];
  const rank = (u: VehicleUsage): number => {
    const number = u.vehicle.number.toLowerCase();
    if (number === q) return 0;
    if (number.startsWith(q)) return 1;
    if (number.includes(q)) return 2;
    return u.vehicle.model?.toLowerCase().includes(q) ? 3 : -1;
  };
  return usage
    .map((u) => ({ u, r: rank(u) }))
    .filter((x) => x.r >= 0)
    .sort((a, b) => a.r - b.r || a.u.vehicle.number.localeCompare(b.u.vehicle.number, undefined, { numeric: true }))
    .slice(0, limit)
    .map((x) => x.u);
}

/** "back after 1.5 days" or "not back yet (3 days)". */
export function daysOutText(b: Breakdown): string {
  const days = `${b.daysOut} ${b.daysOut === 1 ? 'day' : 'days'}`;
  return b.returned ? `back after ${days}` : `not back yet (${days})`;
}

/** Share in whole percent; 0 of 0 is 0. */
export function percent(part: number, whole: number): number {
  return whole > 0 ? Math.round((part / whole) * 100) : 0;
}

/** Whether a window is a day or less: then a bus's runs are shown one per row. */
export function isShortWindow(from: string, to: string): boolean {
  return Date.parse(to) - Date.parse(from) <= DAY_MS + 60_000;
}

/** A row of the run timeline: bars between `from` and `to` (seconds on a common axis) and the breaks between. */
export interface TimelineRow {
  key: string;
  label: string;
  runs: { from: number; to: number; text: string; title: string; detail: string }[];
  breaks: { from: number; to: number }[];
}

function runText(run: { line: string; brigade: string | null; headsign: string | null }): string {
  return `line ${run.line}${run.brigade ? `, brigade ${run.brigade}` : ''}${run.headsign ? ` → ${run.headsign}` : ''}`;
}

const DAY_FORMAT = new Intl.DateTimeFormat('en-GB', { weekday: 'short', day: 'numeric', month: 'short' });

/** "Mon 16 Nov" for a yyyy-MM-dd day. */
export function dayLabel(day: string): string {
  return DAY_FORMAT.format(new Date(`${day}T12:00:00`));
}

/**
 * A bus's timeline: one row per service day (times of day on the axis), or for a window of a day or less, one row
 * per run (times since the first day's start, so a run after midnight follows on).
 */
export function timelineRows(detail: VehicleDetail, perRun: boolean): TimelineRow[] {
  if (!detail.days.length) return [];
  if (!perRun) {
    return detail.days.map((d) => ({
      key: d.day,
      label: dayLabel(d.day),
      runs: d.runs.map((r) => ({
        from: r.from,
        to: r.to,
        text: r.line,
        title: `${clock(r.from)}–${clock(r.to)}`,
        detail: runText(r),
      })),
      breaks: d.breaks,
    }));
  }
  const origin = Date.parse(detail.days[0].start);
  return detail.days.flatMap((d) => {
    const offset = (Date.parse(d.start) - origin) / 1000;
    return d.runs.map((r) => ({
      key: `${d.day}|${r.from}`,
      label: `${r.line} ${clock(r.from + offset)}`,
      runs: [
        {
          from: r.from + offset,
          to: r.to + offset,
          text: r.headsign ?? r.line,
          title: `${clock(r.from)}–${clock(r.to)}`,
          detail: runText(r),
        },
      ],
      breaks: [],
    }));
  });
}

/** Hours per day, a day with an abnormal trip marked. */
export function dailyBars(detail: VehicleDetail): Bar[] {
  const incidentDays = new Set(detail.incidents.map((i) => i.day));
  return detail.days.map((d) => {
    const incidents = detail.incidents.filter((i) => i.day === d.day).length;
    return {
      key: d.day,
      label: dayLabel(d.day).replace(/^\w+ /, ''),
      value: d.hours,
      title: `${dayLabel(d.day)}: ${d.hours.toFixed(1)} h`,
      detail: incidents ? `${incidents} abnormal trip${incidents > 1 ? 's' : ''}` : `${d.runs.length} runs`,
      marked: incidentDays.has(d.day),
    };
  });
}

/** Every bus's hours, in the given order (the most used first). */
export function usageBars(usage: VehicleUsage[]): Bar[] {
  return usage.map((u) => ({
    key: u.vehicle.id,
    label: u.vehicle.number,
    value: u.hours,
    title: `${u.vehicle.number}: ${u.hours.toFixed(1)} h`,
    detail: [u.vehicle.model, u.lines.length ? `lines ${u.lines.join(', ')}` : 'not in service']
      .filter(Boolean)
      .join(' · '),
  }));
}

/** "1.5 h" axis labels. */
export const hoursAxis = (v: number) => `${v} h`;
