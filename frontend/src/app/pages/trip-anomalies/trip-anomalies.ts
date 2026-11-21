import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { Store } from '@ngrx/store';
import { concat, distinctUntilChanged, interval, map, of, switchMap } from 'rxjs';
import { AbnormalTrip, TripAnomalyKind } from '../../core/models/models';
import { PathLine, PathMap } from '../../shared/map/path-map';
import { cssColor } from '../../shared/palette';
import { TimeWindowPicker } from '../../shared/time-window/time-window-picker';
import {
  TimeWindow,
  readWindow,
  sameQuery,
  windowLength,
  windowQuery,
} from '../../shared/time-window/time-window';
import { localTime } from '../../shared/format/time';
import { TripAnomaliesActions } from '../../store/trip-anomalies/trip-anomalies.actions';
import { tripAnomaliesFeature } from '../../store/trip-anomalies/trip-anomalies.reducer';
import {
  KINDS,
  abnormalCount,
  changeFateLabel,
  coverage,
  joinedAt,
  stoodLabel,
  kindLabel,
  replacementFrom,
  percent,
  signedDelay,
} from './trip-kinds';

/** Rows of the trips table shown at first, and added by "Show more". */
export const PAGE_ROWS = 50;
/** Lines listed before "Show all lines". */
export const FIRST_LINES = 12;
/** How often short windows reload. */
export const REFRESH_MS = 60_000;
/** Preset windows up to this long move on (and reload) every minute. */
export const REFRESH_UP_TO_MS = 12 * 3_600_000;
export const DEFAULT_PRESET = '24h';

/** Whether a window reloads every minute: a preset (ending now) of up to 12 hours. */
export function refreshes(w: TimeWindow): boolean {
  const length = windowLength(w);
  return w.preset !== null && length !== null && length <= REFRESH_UP_TO_MS;
}

/**
 * Abnormal trips: every trip scheduled to leave in the window, judged against the recorded positions, with counts
 * by outcome and per line, and a table of the trips that weren't run in full on their route: cancelled, missed,
 * lost, short-turned, detours and gaps in tracking. The window is in the URL; short windows refresh every minute.
 */
@Component({
  selector: 'app-trip-anomalies',
  imports: [DatePipe, TimeWindowPicker, PathMap],
  templateUrl: './trip-anomalies.html',
  styleUrl: './trip-anomalies.scss',
})
export class TripAnomalies {
  private readonly store = inject(Store);

  protected readonly report = this.store.selectSignal(tripAnomaliesFeature.selectReport);
  protected readonly loading = this.store.selectSignal(tripAnomaliesFeature.selectLoading);

  protected readonly defaultPreset = DEFAULT_PRESET;
  protected readonly kinds = KINDS;
  private readonly query$ = inject(ActivatedRoute).queryParamMap.pipe(
    map((params) => ({ params, window: readWindow(params, DEFAULT_PRESET) })),
    distinctUntilChanged(sameQuery),
  );
  protected readonly window = toSignal(this.query$.pipe(map((q) => q.window)), {
    requireSync: true,
  });
  protected readonly refreshing = computed(() => refreshes(this.window()));

  /** The kinds shown in the table (none chosen: all), and one line or all. */
  protected readonly shownKinds = signal<TripAnomalyKind[]>([]);
  protected readonly line = signal<string | null>(null);
  /** One bus: the trips it ran (or was replaced on) only. */
  protected readonly bus = signal<string | null>(null);
  protected readonly rowsShown = signal(PAGE_ROWS);
  protected readonly allLines = signal(false);

  protected readonly filtered = computed(() => {
    const kinds = this.shownKinds();
    const line = this.line();
    const bus = this.bus();
    return (this.report()?.trips ?? []).filter(
      (t) =>
        (!kinds.length || kinds.includes(t.kind)) &&
        (!line || t.line === line) &&
        (!bus || t.vehicles.includes(bus)),
    );
  });
  /** Buses on the abnormal trips, for the bus filter's suggestions. */
  protected readonly busOptions = computed(() =>
    [...new Set((this.report()?.trips ?? []).flatMap((t) => t.vehicles))].sort((a, b) =>
      a.localeCompare(b, undefined, { numeric: true }),
    ),
  );
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
  protected readonly stoodLabel = stoodLabel;
  protected readonly abnormalCount = abnormalCount;
  protected readonly firstLines = FIRST_LINES;

  constructor() {
    // Load the window; short windows again every minute.
    this.query$
      .pipe(
        switchMap(({ params, window }) => {
          this.rowsShown.set(PAGE_ROWS);
          return refreshes(window)
            ? concat(
                of(window),
                interval(REFRESH_MS).pipe(map(() => readWindow(params, DEFAULT_PRESET))),
              )
            : of(window);
        }),
        takeUntilDestroyed(),
      )
      .subscribe((window) =>
        this.store.dispatch(TripAnomaliesActions.load({ window: windowQuery(window) })),
      );
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

  protected setBus(bus: string): void {
    this.bus.set(bus.trim() || null);
    this.rowsShown.set(PAGE_ROWS);
  }

  protected clearFilters(): void {
    this.shownKinds.set([]);
    this.line.set(null);
    this.bus.set(null);
    this.rowsShown.set(PAGE_ROWS);
  }

  /** A detour on a map: the usual route around it (blue), and the bus's path with its reports (orange). */
  protected detourLines(t: AbnormalTrip): PathLine[] {
    return [
      {
        points: t.detourRoute,
        color: cssColor('--chart-line', '#1e88e5'),
        label: 'Usual route',
        weight: 6,
        opacity: 0.75,
      },
      {
        points: t.detourPath,
        color: cssColor('--chart-series-2', '#eb6834'),
        label: "The bus's path (dots: its reports)",
        dots: true,
      },
    ];
  }

  protected showMore(): void {
    this.rowsShown.set(this.rowsShown() + PAGE_ROWS);
  }
}
