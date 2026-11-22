import { DestroyRef, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { BrigadeApi } from '../../core/api/api.services';
import { BrigadeSchedule, ScheduledRun } from '../../core/models/models';
import { escapeHtml } from '../format/html';
import { localTime } from '../format/time';
import { t } from '../../core/i18n/t';

/** Today's schedule is fetched again after this long (timetable changes during the day are rare but happen). */
const TODAY_MAX_AGE_MS = 10 * 60_000;
/** The service day starts at 03:00 Warsaw time. */
const SERVICE_DAY_START_MS = 3 * 3_600_000;
const WARSAW_DATE = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Warsaw' });

/** A brigade's schedule for one service day: loading, loaded, or failed. */
export interface ScheduleState {
  loadedAt: number;
  schedule: BrigadeSchedule | null;
  failed: boolean;
}

/** What is left of a brigade's day at `now`: the run under way (marked), then the runs still to come. */
export function remainingRuns(
  runs: ScheduledRun[],
  now: Date,
): { run: ScheduledRun; now: boolean }[] {
  const t = now.getTime();
  return runs
    .filter((run) => Date.parse(run.arrival) > t)
    .map((run) => ({ run, now: Date.parse(run.departure) <= t }));
}

/** The service day (yyyy-MM-dd, 03:00 to 03:00 Warsaw time) that `at` falls in; close enough across DST changes. */
export function serviceDayOf(at: Date): string {
  return WARSAW_DATE.format(new Date(at.getTime() - SERVICE_DAY_START_MS));
}

/**
 * Brigade schedules for map popups, fetched on demand and kept per brigade and service day. Call in a component's
 * injection context; `loaded` runs when a schedule arrives (or fails), so open popups can be redrawn.
 */
export class BrigadeSchedules {
  private readonly api = inject(BrigadeApi);
  private readonly destroyRef = inject(DestroyRef);
  private readonly states = new Map<string, ScheduleState>();

  constructor(private readonly loaded: (brigade: string) => void) {}

  /**
   * The brigade's schedule for the service day of `at` (null: today), fetching it when it isn't at hand or today's
   * is getting old.
   */
  get(brigade: string, at: Date | null = null): ScheduleState {
    const key = this.key(brigade, at);
    const known = this.states.get(key);
    // Loading, or loaded: past days don't change, today's is refreshed now and then. Failures are retried.
    if (known && !known.failed) {
      if (!known.schedule || at || Date.now() - known.loadedAt < TODAY_MAX_AGE_MS) return known;
    }
    const loading = { loadedAt: Date.now(), schedule: known?.schedule ?? null, failed: false };
    this.states.set(key, loading);
    this.api
      .schedule(brigade, at ?? undefined)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (schedule) =>
          this.settle(key, brigade, { loadedAt: Date.now(), schedule, failed: false }),
        error: () => this.settle(key, brigade, { loadedAt: 0, schedule: null, failed: true }),
      });
    // The answer may already be in (e.g. from a cache): return the latest state.
    return this.states.get(key)!;
  }

  /** What is at hand for the brigade's day, without fetching. */
  peek(brigade: string, at: Date | null = null): ScheduleState | undefined {
    return this.states.get(this.key(brigade, at));
  }

  private key(brigade: string, at: Date | null): string {
    return `${brigade}@${at ? serviceDayOf(at) : 'today'}`;
  }

  private settle(key: string, brigade: string, state: ScheduleState): void {
    this.states.set(key, state);
    this.loaded(brigade);
  }
}

/**
 * Popup section: the brigade and what is left of its day at `at`, the run under way first. `today` words it for
 * the live map; otherwise for a past moment (replays).
 */
export function brigadeSection(
  brigade: string | null,
  state: ScheduleState | undefined,
  at: Date,
  today: boolean,
): string {
  if (!brigade) return '';
  const title = `<div class="brigade"><strong>${escapeHtml(t('brigadeTip.brigade', { brigade }))}</strong>`;
  const note = (key: string) =>
    `${title}<br><span class="muted">${escapeHtml(t(key))}</span></div>`;
  if (!state || (!state.schedule && !state.failed)) return note('brigadeTip.loading');
  if (!state.schedule) return note('brigadeTip.unavailable');
  if (!state.schedule.runs.length)
    return note(today ? 'brigadeTip.notToday' : 'brigadeTip.notThatDay');
  const runs = remainingRuns(state.schedule.runs, at);
  if (!runs.length) return note(today ? 'brigadeTip.noMoreToday' : 'brigadeTip.noMoreThatDay');
  const rows = runs
    .map(
      ({ run, now }) =>
        `<li${now ? ' class="now"' : ''}><span class="t">${localTime(run.departure)}–${localTime(run.arrival)}</span> ` +
        `<strong>${escapeHtml(run.line)}</strong> ${escapeHtml(run.from)} → ${escapeHtml(run.to)}</li>`,
    )
    .join('');
  const rest = escapeHtml(t(today ? 'brigadeTip.restOfToday' : 'brigadeTip.restOfThatDay'));
  return `${title} <span class="muted">· ${rest}</span><ol class="runs">${rows}</ol></div>`;
}
