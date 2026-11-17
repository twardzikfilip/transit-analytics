import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { concat, distinctUntilChanged, interval, map, of, switchMap } from 'rxjs';
import { TripAnomalyKind, TripWindow } from '../../core/models/models';
import { localTime } from '../../shared/format/time';
import { TripAnomaliesActions } from '../../store/trip-anomalies/trip-anomalies.actions';
import { tripAnomaliesFeature } from '../../store/trip-anomalies/trip-anomalies.reducer';
import {
  DEFAULT_TRIP_WINDOW,
  KINDS,
  TRIP_WINDOWS,
  abnormalCount,
  changeFateLabel,
  coverage,
  joinedAt,
  kindLabel,
  replacementFrom,
  parseTripWindow,
  percent,
  signedDelay,
} from './trip-kinds';

/** Rows of the trips table shown at first, and added by "Show more". */
export const PAGE_ROWS = 50;
/** Lines listed before "Show all lines". */
export const FIRST_LINES = 12;
/** How often short windows reload. */
export const REFRESH_MS = 60_000;

/**
 * Abnormal trips: every trip scheduled to leave in the window, judged against the recorded positions, with counts
 * by outcome and per line, and a table of the trips that weren't run in full on their route: cancelled, missed,
 * lost, short-turned, detours and gaps in tracking. The window is in the URL; short windows refresh every minute.
 */
@Component({
  selector: 'app-trip-anomalies',
  imports: [DatePipe],
  templateUrl: './trip-anomalies.html',
  styleUrl: './trip-anomalies.scss',
})
export class TripAnomalies {
  private readonly store = inject(Store);
  private readonly router = inject(Router);

  protected readonly report = this.store.selectSignal(tripAnomaliesFeature.selectReport);
  protected readonly loading = this.store.selectSignal(tripAnomaliesFeature.selectLoading);
  protected readonly error = this.store.selectSignal(tripAnomaliesFeature.selectError);

  protected readonly windows = TRIP_WINDOWS;
  protected readonly kinds = KINDS;
  private readonly window$ = inject(ActivatedRoute).queryParamMap.pipe(
    map((p) => parseTripWindow(p.get('window'))),
    distinctUntilChanged(),
  );
  protected readonly window = toSignal(this.window$, { requireSync: true });
  protected readonly refreshing = computed(
    () => TRIP_WINDOWS.find((w) => w.key === this.window())?.refresh ?? false,
  );

  /** The kinds shown in the table (none chosen: all), and one line or all. */
  protected readonly shownKinds = signal<TripAnomalyKind[]>([]);
  protected readonly line = signal<string | null>(null);
  protected readonly rowsShown = signal(PAGE_ROWS);
  protected readonly allLines = signal(false);

  protected readonly filtered = computed(() => {
    const kinds = this.shownKinds();
    const line = this.line();
    return (this.report()?.trips ?? []).filter(
      (t) => (!kinds.length || kinds.includes(t.kind)) && (!line || t.line === line),
    );
  });
  protected readonly rows = computed(() => this.filtered().slice(0, this.rowsShown()));
  /** Lines with abnormal trips, for the table (first few unless all are shown) and the filter. */
  protected readonly abnormalLines = computed(() =>
    (this.report()?.lines ?? []).filter((l) => abnormalCount(l) > 0),
  );
  protected readonly lines = computed(() =>
    this.allLines() ? this.abnormalLines() : this.abnormalLines().slice(0, FIRST_LINES),
  );
  protected readonly lineOptions = computed(() =>
    this.abnormalLines()
      .map((l) => l.line)
      .sort((a, b) => a.localeCompare(b, undefined, { numeric: true })),
  );

  protected readonly time = localTime;
  protected readonly percent = percent;
  protected readonly signedDelay = signedDelay;
  protected readonly coverage = coverage;
  protected readonly kindLabel = kindLabel;
  protected readonly changeFateLabel = changeFateLabel;
  protected readonly replacementFrom = replacementFrom;
  protected readonly joinedAt = joinedAt;
  protected readonly abnormalCount = abnormalCount;
  protected readonly firstLines = FIRST_LINES;

  constructor() {
    // Load the window; short windows again every minute.
    this.window$
      .pipe(
        switchMap((window) =>
          TRIP_WINDOWS.find((w) => w.key === window)?.refresh
            ? concat(of(window), interval(REFRESH_MS).pipe(map(() => window)))
            : of(window),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((window) => this.store.dispatch(TripAnomaliesActions.load({ window })));
  }

  protected setWindow(window: TripWindow): void {
    this.rowsShown.set(PAGE_ROWS);
    void this.router.navigate([], {
      queryParams: { window: window === DEFAULT_TRIP_WINDOW ? null : window },
      queryParamsHandling: 'merge',
    });
  }

  /** Shows only this kind, or all kinds again when it is the only one shown. */
  protected toggleKind(kind: TripAnomalyKind): void {
    const kinds = this.shownKinds();
    this.shownKinds.set(kinds.length === 1 && kinds[0] === kind ? [] : [kind]);
    this.rowsShown.set(PAGE_ROWS);
  }

  protected setLine(line: string | null): void {
    this.line.set(line || null);
    this.rowsShown.set(PAGE_ROWS);
  }

  protected clearFilters(): void {
    this.shownKinds.set([]);
    this.line.set(null);
    this.rowsShown.set(PAGE_ROWS);
  }

  protected showMore(): void {
    this.rowsShown.set(this.rowsShown() + PAGE_ROWS);
  }
}
