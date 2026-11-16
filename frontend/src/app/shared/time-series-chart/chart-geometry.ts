/** Pure helpers for the time-series chart: scales, ticks and splitting the line where data is missing. */

export interface ChartPoint {
  time: number; // epoch ms
  value: number;
}

/**
 * Round, evenly spaced y ticks that always include 0, e.g. max 197 → [0, 50, 100, 150, 200]; values below zero
 * extend the axis downwards (−0.5 … 2 → [−1, 0, 1, 2]).
 */
export function niceTicks(max: number, targetCount = 5, min = 0): number[] {
  const lo = Math.min(0, min);
  const hi = Math.max(0, max);
  if (!(hi - lo > 0)) return [0, 1];
  const rough = (hi - lo) / targetCount;
  const magnitude = 10 ** Math.floor(Math.log10(rough));
  const step =
    [1, 2, 2.5, 5, 10].map((m) => m * magnitude).find((s) => s >= rough) ?? 10 * magnitude;
  const ticks: number[] = [];
  for (let v = Math.floor(lo / step) * step; v < hi + step; v += step)
    ticks.push(Math.round(v * 1e6) / 1e6);
  return ticks;
}

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const TIME_STEPS = [
  5 * MINUTE,
  15 * MINUTE,
  30 * MINUTE,
  HOUR,
  2 * HOUR,
  3 * HOUR,
  6 * HOUR,
  12 * HOUR,
  DAY,
  2 * DAY,
  7 * DAY,
];

/**
 * Tick positions for the time axis: the smallest round interval giving at most `maxTicks` ticks, aligned to local
 * clock time (so 06:00, 12:00 … rather than 05:37).
 */
export function timeTicks(
  from: number,
  to: number,
  maxTicks: number,
): { ticks: number[]; interval: number } {
  const span = to - from;
  const interval =
    TIME_STEPS.find((s) => span / s <= maxTicks) ?? TIME_STEPS[TIME_STEPS.length - 1];
  // Align to local time: the offset of `from` from local midnight.
  const start = new Date(from);
  const localMidnight = new Date(start.getFullYear(), start.getMonth(), start.getDate()).getTime();
  let t = localMidnight + Math.ceil((from - localMidnight) / interval) * interval;
  const ticks: number[] = [];
  for (; t <= to; t += interval) ticks.push(t);
  return { ticks, interval };
}

/**
 * Shortest pause in recording that breaks the line, whatever the bucket size: one missed poll (positions are recorded
 * every 20 seconds, earlier every minute) is not a break, as vehicles count as running for a minute after a report.
 */
export const MIN_GAP_MS = 90_000;

/**
 * Splits points into runs without gaps. A gap is a jump of more than 1.5 buckets (at least one bucket in which nothing
 * was recorded) and more than `minGap`; the line breaks there instead of drawing a slope through the missing time.
 */
export function segments(points: ChartPoint[], stepMs: number, minGap = 0): ChartPoint[][] {
  const gap = Math.max(stepMs * 1.5, minGap);
  const runs: ChartPoint[][] = [];
  let run: ChartPoint[] = [];
  for (const p of points) {
    if (run.length && p.time - run[run.length - 1].time > gap) {
      runs.push(run);
      run = [];
    }
    run.push(p);
  }
  if (run.length) runs.push(run);
  return runs;
}

/** Index of the point whose time is closest to `time` (points sorted by time). */
export function nearestIndex(points: ChartPoint[], time: number): number {
  if (!points.length) return -1;
  let lo = 0;
  let hi = points.length - 1;
  while (hi - lo > 1) {
    const mid = (lo + hi) >> 1;
    if (points[mid].time <= time) lo = mid;
    else hi = mid;
  }
  return Math.abs(points[lo].time - time) <= Math.abs(points[hi].time - time) ? lo : hi;
}
