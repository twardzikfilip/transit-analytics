import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Store } from '@ngrx/store';
import { injectSetQuery } from '../../shared/url-query';
import { combineLatest, startWith, timer } from 'rxjs';
import { Pager, rowNumber } from '../../shared/pager/pager';
import { TimeSeriesChart } from '../../shared/time-series-chart/time-series-chart';
import {
  HistoryQuery,
  PositionHistoryActions,
} from '../../store/position-history/position-history.actions';
import { positionHistoryFeature } from '../../store/position-history/position-history.reducer';
import { formatDelay } from '../../shared/format/time';
import { TimeWindowPicker } from '../../shared/time-window/time-window-picker';
import { POLL_MS, readWindow, refreshMs, windowQuery } from '../../shared/time-window/time-window';
import { bandRows, formatShare, isOutlier, windowLabel } from './delay-bands';
import { ReportSortKey, formatReportSort, nextReportSort, parseReportSort } from './report-sort';

/**
 * Recorded vehicle positions over time for a window and line: vehicles running and mean delay (per poll, i.e. per
 * minute, for windows up to 24 h), the delay distribution, and the raw reports.
 */
@Component({
  selector: 'app-positions',
  imports: [TimeSeriesChart, Pager, DecimalPipe, DatePipe, RouterLink, TimeWindowPicker],
  templateUrl: './positions.html',
  styleUrl: './positions.scss',
})
export class Positions {
  private readonly store = inject(Store);
  private readonly navigate = injectSetQuery();
  private readonly params = toSignal(inject(ActivatedRoute).queryParamMap, { requireSync: true });

  protected readonly series = this.store.selectSignal(positionHistoryFeature.selectSeries);
  protected readonly routes = this.store.selectSignal(positionHistoryFeature.selectRoutes);
  protected readonly samples = this.store.selectSignal(positionHistoryFeature.selectSamples);
  protected readonly loadingSeries = this.store.selectSignal(
    positionHistoryFeature.selectLoadingSeries,
  );
  protected readonly loadingSamples = this.store.selectSignal(
    positionHistoryFeature.selectLoadingSamples,
  );
  protected readonly error = this.store.selectSignal(positionHistoryFeature.selectError);
  protected readonly query = this.store.selectSignal(positionHistoryFeature.selectQuery);
  private readonly delays = this.store.selectSignal(positionHistoryFeature.selectDelays);
  protected readonly delayBands = computed(() => bandRows(this.delays()));
  /** The window and line the loaded results cover, e.g. "Mon 16 Nov, 08:36 – 14:36 · all lines". */
  protected readonly coverage = computed(() => {
    const q = this.query();
    if (!q) return '';
    // All time: the series says where the recording starts.
    const from = q.from ?? this.series()?.from ?? null;
    const window = from
      ? windowLabel(from, q.to)
      : `until ${windowLabel(q.to, q.to).split(' – ')[0]}`;
    return `${window} · ${q.route ? 'line ' + q.route : 'all lines'}`;
  });

  protected readonly showTable = signal(false);

  protected readonly reportColumns: { key: ReportSortKey; label: string }[] = [
    { key: 'time', label: 'Time' },
    { key: 'vehicle', label: 'Vehicle' },
    { key: 'line', label: 'Line' },
    { key: 'duty', label: 'Duty' },
    { key: 'towards', label: 'Towards' },
    { key: 'delay', label: 'Delay' },
    { key: 'nextStop', label: 'Next stop' },
  ];

  /** Sort of the reports table, from the URL (?sort=delay,desc); newest first by default. */
  protected readonly reportSort = computed(() => parseReportSort(this.params().get('sort')));

  /** Hide reports more than 30 min late (?hideOutliers=1). */
  protected readonly hideOutliers = computed(() => this.params().get('hideOutliers') === '1');
  protected readonly isOutlier = isOutlier;

  /** URL state. */
  protected readonly route = computed(() => this.params().get('route') ?? '');

  /**
   * Chart inputs: the span is always the selected window (the query), so the axis follows a new window at once;
   * the points are the loaded series' within it. While another window loads, the previous one's points inside the
   * new window stay (they are real data there) and the rest are left out, so nothing from outside it is shown.
   */
  protected readonly charts = computed(() => {
    const s = this.series();
    if (!s) return null;
    const q = this.query();
    // All time: the series says where the recording starts.
    const from = q?.from ? Date.parse(q.from) : Date.parse(s.from);
    const to = q ? Date.parse(q.to) : Date.parse(s.to);
    const points = s.points
      .map((p) => ({ ...p, t: Date.parse(p.time) }))
      .filter((p) => p.t >= from && p.t < to);
    return {
      from,
      to,
      stepMs: s.stepSeconds * 1000,
      rows: points,
      vehicles: points.map((p) => ({ time: p.t, value: p.vehicles })),
      delay: points
        .filter((p) => p.meanDelaySeconds !== null)
        .map((p) => ({ time: p.t, value: p.meanDelaySeconds! / 60 })),
    };
  });

  /** Chart formatters: delay values are minutes. */
  protected readonly delayTooltip = (minutes: number) => formatDelay(minutes * 60);
  protected readonly delayAxis = (minutes: number) => `${minutes} min`;
  protected readonly formatDelay = formatDelay;
  protected readonly formatShare = formatShare;

  /** "every 10 s (each poll)", "every minute", or "per 10 min" for longer windows. */
  protected readonly stepLabel = computed(() => {
    const seconds = this.series()?.stepSeconds ?? 60;
    if (seconds < 60) return `every ${seconds} s (each poll)`;
    if (seconds === 60) return 'every minute';
    if (seconds % 86_400 === 0) return `per ${seconds / 86_400} day`;
    if (seconds % 3_600 === 0) return `per ${seconds / 3_600} h`;
    return `per ${seconds / 60} min`;
  });

  constructor() {
    // Load whenever the URL changes, and as preset windows move (they end "now"): each poll for windows of up to an
    // hour, else each minute.
    const route = inject(ActivatedRoute);
    combineLatest([route.queryParamMap, timer(POLL_MS, POLL_MS).pipe(startWith(-1))])
      .pipe(takeUntilDestroyed())
      .subscribe(([params, tick]) => {
        const w = this.resolve(new Date(), params);
        if (tick >= 0 && !w.preset) return; // custom windows don't move
        if (tick >= 0 && (tick + 1) % (refreshMs(w) / POLL_MS) !== 0) return;
        const query: HistoryQuery = {
          ...windowQuery(w),
          route: params.get('route') || null,
        };
        this.store.dispatch(PositionHistoryActions.load({ query }));
        this.store.dispatch(
          PositionHistoryActions.loadSamples({
            query,
            page: Math.max(0, (Number(params.get('page')) || 1) - 1),
            sort: formatReportSort(parseReportSort(params.get('sort'))),
            excludeOutliers: params.get('hideOutliers') === '1',
          }),
        );
      });
  }

  private resolve(now: Date, params = this.params()) {
    return readWindow(params, '24h', now);
  }

  protected setRoute(route: string): void {
    this.navigate({ route: route || null, page: null });
  }

  /** Sorting goes back to the first page. */
  protected sortReports(key: ReportSortKey): void {
    this.navigate({ sort: formatReportSort(nextReportSort(this.reportSort(), key)), page: null });
  }

  protected reportAriaSort(key: ReportSortKey): 'ascending' | 'descending' | null {
    const s = this.reportSort();
    return s.key === key ? (s.descending ? 'descending' : 'ascending') : null;
  }

  /** Showing or hiding outliers goes back to the first page. */
  protected setHideOutliers(hide: boolean): void {
    this.navigate({ hideOutliers: hide ? '1' : null, page: null });
  }

  protected setPage(page: number): void {
    this.navigate({ page: page > 0 ? page + 1 : null });
  }

  protected index(i: number): number {
    return rowNumber(this.samples()?.page, i);
  }
}
