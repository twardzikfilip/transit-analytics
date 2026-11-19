import { ReplayResponse } from '../../core/models/models';
import { DELAY_STOPS, NO_DELAY_COLOR } from '../../shared/palette';

/** A vehicle's position at one moment of the replay. */
export interface ReplayPosition {
  vehicle: string;
  line: string | null;
  /** Vehicle duty, e.g. 024-02. */
  brigade: string | null;
  latitude: number;
  longitude: number;
  delaySeconds: number | null;
}

export interface ReplayFrame {
  time: number; // epoch ms
  /** By vehicle number. */
  positions: Map<string, ReplayPosition>;
}

export interface Replay {
  from: number;
  to: number;
  stepMs: number;
  frames: ReplayFrame[];
}

/** Turns the compact API rows [vehicleIndex, lat, lon, delay, lineIndex, brigadeIndex] into lookups by vehicle. */
export function parseReplay(raw: ReplayResponse): Replay {
  return {
    from: Date.parse(raw.from),
    to: Date.parse(raw.to),
    stepMs: raw.stepSeconds * 1000,
    frames: raw.frames.map((f) => {
      const positions = new Map<string, ReplayPosition>();
      for (const [v, latitude, longitude, delaySeconds, l, b] of f.positions) {
        const vehicle = raw.vehicles[v];
        positions.set(vehicle, {
          vehicle,
          line: l === null ? null : raw.lines[l],
          brigade: b === null || b === undefined ? null : raw.brigades[b],
          latitude,
          longitude,
          delaySeconds,
        });
      }
      return { time: Date.parse(f.time), positions };
    }),
  };
}

/**
 * Where every vehicle is at time `t`. Between two consecutive frames vehicles glide from one position to the next;
 * across a gap in the recording (frames more than 1.5 steps apart) they stay where they were last seen. Before the
 * first frame nothing is shown; after the last, its positions are kept.
 */
export function positionsAt(replay: Replay, t: number): ReplayPosition[] {
  const frames = replay.frames;
  if (!frames.length || t < frames[0].time) return [];
  let lo = 0;
  let hi = frames.length - 1;
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1;
    if (frames[mid].time <= t) lo = mid;
    else hi = mid - 1;
  }
  const current = frames[lo];
  const next = frames[lo + 1];
  if (!next || next.time - current.time > replay.stepMs * 1.5)
    return [...current.positions.values()];
  const k = (t - current.time) / (next.time - current.time);
  return [...current.positions.values()].map((p) => {
    const n = next.positions.get(p.vehicle);
    if (!n) return p;
    return {
      ...p,
      latitude: p.latitude + (n.latitude - p.latitude) * k,
      longitude: p.longitude + (n.longitude - p.longitude) * k,
      // Delay changes in steps; switch at the halfway point rather than blending.
      delaySeconds: k < 0.5 ? p.delaySeconds : n.delaySeconds,
      line: k < 0.5 ? p.line : n.line,
      brigade: k < 0.5 ? p.brigade : n.brigade,
    };
  });
}

export { DELAY_STOPS, NO_DELAY_COLOR, delayColor } from '../../shared/palette';

/** Playback speeds: replay seconds per real second. */
export const SPEEDS: { value: number; label: string }[] = [
  { value: 1, label: 'Real time' },
  { value: 30, label: '30 s per second' },
  { value: 60, label: '1 min per second' },
  { value: 300, label: '5 min per second' },
  { value: 900, label: '15 min per second' },
  { value: 3600, label: '1 h per second' },
];

/** The fastest speed that still lets the window play for at least ~30 seconds. */
export function defaultSpeed(windowMs: number): number {
  const target = windowMs / 1000 / 30;
  return [...SPEEDS].reverse().find((s) => s.value <= target)?.value ?? SPEEDS[0].value;
}

/**
 * Mean delay in seconds of the vehicles shown at one moment; early vehicles count as negative. Vehicles without a
 * delay are left out. Null when none has one.
 */
export function averageDelay(
  positions: ReplayPosition[],
): { seconds: number; vehicles: number } | null {
  let sum = 0;
  let count = 0;
  for (const p of positions) {
    if (p.delaySeconds !== null) {
      sum += p.delaySeconds;
      count++;
    }
  }
  return count ? { seconds: sum / count, vehicles: count } : null;
}
