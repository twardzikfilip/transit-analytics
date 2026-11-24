import { Component, computed, inject } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, ParamMap } from '@angular/router';
import { Store } from '@ngrx/store';
import {
  Subject,
  concat,
  debounceTime,
  distinctUntilChanged,
  interval,
  map,
  of,
  switchMap,
} from 'rxjs';
import { PageMeta, TripRunQuery } from '../../core/models/models';
import { Pager } from '../../shared/pager/pager';
import { Skeleton } from '../../shared/skeleton/skeleton';
import { LocalDatePipe } from '../../shared/i18n/locale-pipes';
import { TimeWindowPicker } from '../../shared/time-window/time-window-picker';
import {
  TimeWindow,
  readWindow,
  windowLength,
  windowQuery,
} from '../../shared/time-window/time-window';
import { injectSetQuery } from '../../shared/url-query';
import { TripRunsActions } from '../../store/trip-runs/trip-runs.actions';
import { tripRunsFeature } from '../../store/trip-runs/trip-runs.reducer';
import { REFRESH_MS, TripList } from './trip-list';
import { listSpan } from './trip-layout';
import { t } from '../../core/i18n/t';

export const DEFAULT_PRESET = '1h';
/** Preset windows (ending now) up to this long reload every minute. */
const REFRESH_UP_TO_MS = 12 * 3_600_000;

/** Whether a window reloads every minute: a preset of up to 12 hours. */
export function refreshes(w: TimeWindow): boolean {
  const length = windowLength(w);
  return w.preset !== null && length !== null && length <= REFRESH_UP_TO_MS;
}

/** The query in the URL: the window, the search (?q=) and the page (?page=, from 1). */
export function readQuery(params: ParamMap, now = new Date()): TripRunQuery & { key: string } {
  const window = readWindow(params, DEFAULT_PRESET, now);
  const q = params.get('q')?.trim() || null;
  const page = Math.max(0, Number(params.get('page') ?? 1) - 1 || 0);
  const span = window.preset ?? `${window.from?.toISOString()}|${window.to.toISOString()}`;
  return { window: windowQuery(window), q, page, key: `${span}|${q ?? ''}|${page}` };
}

/**
 * Trips: every trip scheduled to run in the time window (last hour by default), each with what ran on one time line,
 * the scheduled bar over the actual one: the bus's delay along the way in colour, where it stood still, a change of
 * bus and an early end ({@link TripList}). Searchable by bus, brigade, line or destination; open a trip for its delay and speed over
 * the whole trip. The window, search, page and open trip are in the URL; short windows reload every minute.
 */
@Component({
  selector: 'app-trips',
  imports: [TimeWindowPicker, Pager, Skeleton, LocalDatePipe, TripList],
  templateUrl: './trips.html',
  styleUrl: './trips.scss',
})
export class Trips {
  protected readonly t = t;
  private readonly store = inject(Store);
  private readonly setQuery = injectSetQuery();
  private readonly params = toSignal(inject(ActivatedRoute).queryParamMap, { requireSync: true });

  protected readonly result = this.store.selectSignal(tripRunsFeature.selectPage);
  protected readonly loading = this.store.selectSignal(tripRunsFeature.selectLoading);
  protected readonly defaultPreset = DEFAULT_PRESET;
  protected readonly q = computed(() => this.params().get('q') ?? '');
  protected readonly refreshing = computed(() =>
    refreshes(readWindow(this.params(), DEFAULT_PRESET)),
  );
  /** The list's shared time axis. */
  protected readonly span = computed(() => {
    const page = this.result();
    return page && page.trips.length ? listSpan(page) : null;
  });
  protected readonly pageMeta = computed<PageMeta | null>(() => {
    const p = this.result();
    return p
      ? {
          size: p.size,
          number: p.page,
          totalElements: p.total,
          totalPages: Math.max(1, Math.ceil(p.total / p.size)),
        }
      : null;
  });
  /** Whether only the last 24 h of a longer window are listed. */
  protected readonly cut = computed(() => {
    const p = this.result();
    return !!p && p.shownFrom !== p.from;
  });

  private readonly search$ = new Subject<string>();

  constructor() {
    const route = inject(ActivatedRoute);
    // Load on a new window, search or page; preset windows again every minute.
    route.queryParamMap
      .pipe(
        map((params) => ({ params, query: readQuery(params) })),
        distinctUntilChanged((a, b) => a.query.key === b.query.key),
        switchMap(({ params, query }) =>
          refreshes(readWindow(params, DEFAULT_PRESET))
            ? concat(of(query), interval(REFRESH_MS).pipe(map(() => readQuery(params))))
            : of(query),
        ),
        takeUntilDestroyed(),
      )
      .subscribe(({ window, q, page }) =>
        this.store.dispatch(TripRunsActions.load({ query: { window, q, page } })),
      );
    this.search$
      .pipe(debounceTime(300), distinctUntilChanged(), takeUntilDestroyed())
      .subscribe((q) =>
        this.setQuery(
          { q: q.trim() || null, page: null, trip: null, map: null },
          { replaceUrl: true },
        ),
      );
  }

  protected onSearch(value: string): void {
    this.search$.next(value);
  }

  protected setPage(page: number): void {
    this.setQuery({ page: page > 0 ? page + 1 : null, trip: null, map: null });
  }
}
