import { ReplayResponse } from '../../core/models/models';
import {
  averageDelay,
  defaultSpeed,
  delayColor,
  parseReplay,
  positionsAt,
  resolveDelayWindow,
} from './replay';

describe('delay replay', () => {
  const raw: ReplayResponse = {
    from: '2026-11-16T10:00:00Z',
    to: '2026-11-16T11:00:00Z',
    stepSeconds: 60,
    routes: [],
    vehicles: ['230', '009'],
    lines: ['18', '107'],
    frames: [
      {
        time: '2026-11-16T10:00:00Z',
        positions: [
          [0, 53.0, 23.0, 30, 0],
          [1, 53.5, 23.5, null, 1],
        ],
      },
      { time: '2026-11-16T10:01:00Z', positions: [[0, 53.1, 23.2, 400, 0]] },
      { time: '2026-11-16T10:10:00Z', positions: [[0, 53.9, 23.9, 0, 0]] },
    ],
  };
  const replay = parseReplay(raw);
  const at = (iso: string) => positionsAt(replay, Date.parse(iso));

  it('decodes the compact rows', () => {
    expect(replay.frames[0].positions.get('009')).toEqual({
      vehicle: '009',
      line: '107',
      latitude: 53.5,
      longitude: 23.5,
      delaySeconds: null,
    });
  });

  it('glides between consecutive frames and switches the delay halfway', () => {
    const quarter = at('2026-11-16T10:00:15Z');
    const v = quarter.find((p) => p.vehicle === '230')!;
    expect(v.latitude).toBeCloseTo(53.025);
    expect(v.longitude).toBeCloseTo(23.05);
    expect(v.delaySeconds).toBe(30);
    expect(at('2026-11-16T10:00:45Z').find((p) => p.vehicle === '230')!.delaySeconds).toBe(400);
    // 009 is not in the next frame: it stays put until then.
    expect(quarter.find((p) => p.vehicle === '009')!.latitude).toBe(53.5);
  });

  it('holds positions across gaps in the recording and outside the frames', () => {
    expect(at('2026-11-16T10:05:00Z')).toEqual([...replay.frames[1].positions.values()]);
    expect(at('2026-11-16T09:59:00Z')).toEqual([]);
    expect(at('2026-11-16T10:30:00Z')[0].latitude).toBe(53.9);
  });

  it('colours delays from green through yellow and orange to red', () => {
    expect(delayColor(-120)).toBe('#2e9e5b');
    expect(delayColor(60)).toBe('#2e9e5b');
    expect(delayColor(180)).toBe('#f2c12e');
    expect(delayColor(360)).toBe('#f07f22');
    expect(delayColor(600)).toBe('#d7301f');
    expect(delayColor(3600)).toBe('#d7301f');
    expect(delayColor(480)).toBe('#e45821'); // halfway between orange and red
    expect(delayColor(null)).toBe('#8a8f98');
  });

  it('picks a speed that plays a window in about half a minute or more', () => {
    expect(defaultSpeed(15 * 60_000)).toBe(30);
    expect(defaultSpeed(3_600_000)).toBe(60);
    expect(defaultSpeed(86_400_000)).toBe(900);
    expect(defaultSpeed(10_000)).toBe(1);
  });

  it('resolves presets and custom windows', () => {
    const now = new Date(2026, 10, 16, 14, 2, 41);
    const w = resolveDelayWindow({ range: '15m', from: null, to: null }, now);
    expect(w.preset).toBe('15m');
    expect(w.to.getTime() - w.from.getTime()).toBe(15 * 60_000);
    expect(resolveDelayWindow({ range: null, from: null, to: null }, now).preset).toBe('1h');
    expect(
      resolveDelayWindow({ range: null, from: '2026-11-16T06:00', to: '2026-11-16T09:00' }, now)
        .preset,
    ).toBeNull();
  });

  it('averages the delays of the vehicles shown, early ones counting as negative', () => {
    const p = (delaySeconds: number | null) => ({
      vehicle: 'x',
      line: '1',
      latitude: 0,
      longitude: 0,
      delaySeconds,
    });
    expect(averageDelay([p(120), p(-60), p(null), p(300)])).toEqual({ seconds: 120, vehicles: 3 });
    expect(averageDelay([p(null)])).toBeNull();
    expect(averageDelay([])).toBeNull();
    // Interpolated moments use the delays switched at the halfway point.
    expect(averageDelay(at('2026-11-16T10:00:15Z'))).toEqual({ seconds: 30, vehicles: 1 });
  });
});
