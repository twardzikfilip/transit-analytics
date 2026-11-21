import { convertToParamMap } from '@angular/router';
import { toLocalInput } from '../format/time';
import {
  TimeWindow,
  WINDOW_PRESETS,
  customParams,
  presetParams,
  readAgencies,
  readWindow,
  refreshMs,
  resolveWindow,
  sameQuery,
  windowLength,
  windowQuery,
} from './time-window';

describe('time window', () => {
  const now = new Date(2026, 10, 16, 14, 2, 41);
  const preset = (range: string | null) =>
    resolveWindow({ range, from: null, to: null }, now, '24h');

  it('offers the presets from a minute to a month, and all time', () => {
    expect(WINDOW_PRESETS.map((p) => p.label)).toEqual([
      '1 min',
      '5 min',
      '30 min',
      '1 h',
      '2 h',
      '12 h',
      '24 h',
      '3 days',
      '1 week',
      '1 month',
      'All time',
    ]);
  });

  it('ends a preset at the next full minute, or the next poll for an hour or less', () => {
    const w = preset('12h');
    expect(w.preset).toBe('12h');
    expect(toLocalInput(w.to)).toBe('2026-11-16T14:03');
    expect(toLocalInput(w.from!)).toBe('2026-11-16T02:03');
    expect(refreshMs(w)).toBe(60_000);

    const short = preset('5m');
    expect(short.to).toEqual(new Date(2026, 10, 16, 14, 2, 50));
    expect(short.from).toEqual(new Date(2026, 10, 16, 13, 57, 50));
    expect(preset('1m').to).toEqual(new Date(2026, 10, 16, 14, 2, 50));
    expect(refreshMs(short)).toBe(10_000);
    expect(windowLength(short)).toBe(5 * 60_000);
  });

  it('has no start for all time, and falls back to the page default', () => {
    const all = preset('all');
    expect(all.from).toBeNull();
    expect(windowLength(all)).toBeNull();
    expect(refreshMs(all)).toBe(60_000);
    expect(windowQuery(all)).toEqual({ from: null, to: all.to.toISOString() });
    expect(preset('5y').preset).toBe('24h');
    expect(resolveWindow({ range: null, from: null, to: null }, now, '1w').preset).toBe('1w');
    expect(resolveWindow({ range: null, from: null, to: null }, now, 'nonsense').preset).toBe(
      '24h',
    );
  });

  it('takes a custom window from the URL, but not a reversed one', () => {
    const params = convertToParamMap({
      from: '2026-11-16T05:00',
      to: '2026-11-16T17:00',
      agency: ['BKM', 'X'],
    });
    const custom = readWindow(params, '24h', now);
    expect(custom.preset).toBeNull();
    expect(toLocalInput(custom.from!)).toBe('2026-11-16T05:00');
    expect(readAgencies(params)).toEqual(['BKM', 'X']);
    expect(
      resolveWindow({ range: null, from: '2026-11-16T17:00', to: '2026-11-16T05:00' }, now, '24h')
        .preset,
    ).toBe('24h');
  });

  it('builds the URL parameters for a preset or one end of a custom window', () => {
    expect(presetParams('1w')).toEqual({ range: '1w', from: null, to: null });
    const w = preset('1h');
    expect(customParams(w, 'from', '2026-11-16T08:00')).toEqual({
      range: null,
      from: '2026-11-16T08:00',
      to: toLocalInput(w.to),
    });
    expect(customParams(w, 'to', '')).toBeNull();
    // All time has no start to keep: the day before the end.
    expect(customParams(preset('all'), 'to', '2026-11-16T12:00')).toEqual({
      range: null,
      from: toLocalInput(new Date(preset('all').to.getTime() - 86_400_000)),
      to: '2026-11-16T12:00',
    });
  });

  it('compares windows: presets by key, custom ones by time, and the operators', () => {
    const a: { window: TimeWindow; agencies?: string[] } = {
      window: preset('1h'),
      agencies: ['BKM'],
    };
    const later = {
      window: resolveWindow({ range: '1h', from: null, to: null }, new Date(), '24h'),
      agencies: ['BKM'],
    };
    expect(sameQuery(a, later)).toBe(true);
    expect(sameQuery(a, { ...a, agencies: [] })).toBe(false);
    expect(sameQuery(a, { window: preset('2h') })).toBe(false);
    const custom = (to: string) => ({
      window: resolveWindow({ range: null, from: '2026-11-16T05:00', to }, now, '24h'),
    });
    expect(sameQuery(custom('2026-11-16T06:00'), custom('2026-11-16T06:00'))).toBe(true);
    expect(sameQuery(custom('2026-11-16T06:00'), custom('2026-11-16T07:00'))).toBe(false);
  });
});
