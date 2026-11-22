import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { BrigadeApi } from '../../core/api/api.services';
import { BrigadeSchedule, ScheduledRun } from '../../core/models/models';
import { BrigadeSchedules, brigadeSection, remainingRuns, serviceDayOf } from './brigade-schedules';

const run = (departure: string, arrival: string): ScheduledRun => ({
  line: '103',
  headsign: null,
  from: 'A',
  to: 'B',
  departure: `2026-11-16T${departure}:00Z`,
  arrival: `2026-11-16T${arrival}:00Z`,
});

describe('remaining brigade runs', () => {
  it('leaves out finished runs and marks the one under way', () => {
    const runs = [run('06:00', '06:40'), run('07:00', '07:40'), run('08:00', '08:40')];
    expect(remainingRuns(runs, new Date('2026-11-16T07:10:00Z'))).toEqual([
      { run: runs[1], now: true },
      { run: runs[2], now: false },
    ]);
    expect(remainingRuns(runs, new Date('2026-11-16T07:50:00Z'))).toEqual([
      { run: runs[2], now: false },
    ]);
    expect(remainingRuns(runs, new Date('2026-11-16T09:00:00Z'))).toEqual([]);
  });
});

describe('service day', () => {
  it('starts at 03:00 Warsaw time', () => {
    expect(serviceDayOf(new Date('2026-11-16T01:30:00Z'))).toBe('2026-11-15'); // 02:30 in Warsaw
    expect(serviceDayOf(new Date('2026-11-16T02:30:00Z'))).toBe('2026-11-16'); // 03:30
    expect(serviceDayOf(new Date('2026-11-16T22:30:00Z'))).toBe('2026-11-16'); // 23:30
  });
});

describe('brigade schedules', () => {
  const schedule = (brigade: string) => ({ brigade, serviceDay: '2026-11-16', runs: [] });

  function setup(respond: (brigade: string, at?: Date) => Observable<BrigadeSchedule>) {
    const api = { schedule: vi.fn(respond) };
    const loaded = vi.fn();
    TestBed.configureTestingModule({ providers: [{ provide: BrigadeApi, useValue: api }] });
    const schedules = TestBed.runInInjectionContext(() => new BrigadeSchedules(loaded));
    return { api, loaded, schedules };
  }

  it('fetches once per brigade and service day, and peeks without fetching', () => {
    const { api, loaded, schedules } = setup((b) => of(schedule(b)));
    expect(schedules.peek('103-05')).toBeUndefined();
    expect(schedules.get('103-05').schedule?.brigade).toBe('103-05');
    schedules.get('103-05');
    const morning = new Date('2026-11-10T08:00:00Z');
    schedules.get('103-05', morning);
    schedules.get('103-05', new Date('2026-11-10T20:00:00Z'));
    expect(api.schedule.mock.calls).toEqual([
      ['103-05', undefined],
      ['103-05', morning],
    ]);
    expect(loaded).toHaveBeenCalledWith('103-05');
    expect(schedules.peek('103-05', morning)?.schedule).not.toBeNull();
  });

  it('reports failures and tries again on the next look', () => {
    const { api, schedules } = setup(() => throwError(() => new Error('down')));
    expect(schedules.get('103-05').failed).toBe(true);
    schedules.get('103-05');
    expect(api.schedule).toHaveBeenCalledTimes(2);
  });

  it('words the popup for today or a replayed day', () => {
    const at = new Date('2026-11-16T07:10:00Z');
    const state = (runs: ScheduledRun[]) => ({
      loadedAt: 0,
      failed: false,
      schedule: { brigade: '103-05', serviceDay: '2026-11-16', runs },
    });
    const runs = [run('07:00', '07:40'), run('08:00', '08:40')];
    expect(brigadeSection(null, undefined, at, true)).toBe('');
    expect(brigadeSection('103-05', undefined, at, true)).toContain('Loading schedule…');
    expect(
      brigadeSection('103-05', { loadedAt: 0, failed: true, schedule: null }, at, true),
    ).toContain('Schedule unavailable');
    expect(brigadeSection('103-05', state([]), at, false)).toContain(
      'Not in that day&#39;s timetable',
    );
    expect(brigadeSection('103-05', state(runs), new Date('2026-11-16T09:00:00Z'), true)).toContain(
      'No more runs today',
    );
    const html = brigadeSection('103-<5>', state(runs), at, false);
    expect(html).toContain('Brigade 103-&#60;5&#62;');
    expect(html).toContain('rest of that day');
    expect(html.match(/<li/g)).toHaveLength(2);
  });
});
