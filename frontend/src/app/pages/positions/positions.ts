import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Store } from '@ngrx/store';
import { combineLatest, startWith, timer } from 'rxjs';
import { Pager } from '../../shared/pager/pager';
import { TimeSeriesChart } from '../../shared/time-series-chart/time-series-chart';
import { HistoryQuery, PositionHistoryActions } from '../../store/position-history/position-history.actions';
import { positionHistoryFeature } from '../../store/position-history/position-history.reducer';
import { bandRows, formatDelay, formatShare } from './delay-bands';
import { PRESETS, resolveWindow, toLocalInput } from './time-window';

/**
 * Recorded vehicle positions over time for a window and line: vehicles running and mean delay (per poll, i.e. per
 * minute, for windows up to 24 h), the delay distribution, and the raw reports.
 */
@Component({
  selector: 'app-positions',
  imports: [TimeSeriesChart, Pager, DecimalPipe, DatePipe, RouterLink],
  templateUrl: './positions.html',
  styleUrl: './positions.scss',
})
export class Positions {
  private readonly store = inject(Store);
  private readonly router = inject(Router);
  private readonly params = toSignal(inject(ActivatedRoute).queryParamMap, { requireSync: true });

  protected readonly presets = PRESETS;
  protected readonly series = this.store.selectSignal(positionHistoryFeature.selectSeries);
  protected readonly routes = this.store.selectSignal(positionHistoryFeature.selectRoutes);
  protected readonly samples = this.store.selectSignal(positionHistoryFeature.selectSamples);
  protected readonly loadingSeries = this.store.selectSignal(positionHistoryFeature.selectLoadingSeries);
  protected readonly loadingSamples = this.store.selectSignal(positionHistoryFeature.selectLoadingSamples);
  protected readonly error = this.store.selectSignal(positionHistoryFeature.selectError);
  protected readonly query = this.store.selectSignal(positionHistoryFeature.selectQuery);
  protected readonly delayBands = computed(() => bandRows(this.store.selectSignal(positionHistoryFeature.selectDelays)()));

  protected readonly showTable = signal(false);

  /** URL state. */
  protected readonly route = computed(() => this.params().get('route') ?? '');
  protected readonly window = computed(() => this.resolve(new Date()));

  /** Chart inputs derived from the loaded series: vehicles running, and mean delay in minutes. */
  protected readonly charts = computed(() => {
    const s = this.series();
    if (!s) return null;
    const time = (t: string) => Date.parse(t);
    return {
      from: Date.parse(s.from),
      to: Date.parse(s.to),
      stepMs: s.stepSeconds * 1000,
      vehicles: s.points.map((p) => ({ time: time(p.time), value: p.vehicles })),
      delay: s.points
        .filter((p) => p.meanDelaySeconds !== null)
        .map((p) => ({ time: time(p.time), value: p.meanDelaySeconds! / 60 })),
    };
  });

  /** Chart formatters: delay values are minutes. */
  protected readonly delayTooltip = (minutes: number) => formatDelay(minutes * 60);
  protected readonly delayAxis = (minutes: number) => `${minutes} min`;
  protected readonly formatDelay = formatDelay;
  protected readonly formatShare = formatShare;

  /** "every minute (each poll)" or "per 10 min" for longer windows. */
  protected readonly stepLabel = computed(() => {
    const seconds = this.series()?.stepSeconds ?? 60;
    if (seconds === 60) return 'every minute (each poll)';
    if (seconds % 86_400 === 0) return `per ${seconds / 86_400} day`;
    if (seconds % 3_600 === 0) return `per ${seconds / 3_600} h`;
    return `per ${seconds / 60} min`;
  });

  constructor() {
    // Load whenever the URL changes, and every minute for preset windows (they end "now").
    const route = inject(ActivatedRoute);
    combineLatest([route.queryParamMap, timer(60_000, 60_000).pipe(startWith(-1))])
      .pipe(takeUntilDestroyed())
      .subscribe(([params, tick]) => {
        const w = this.resolve(new Date(), params);
        if (tick >= 0 && !w.preset) return; // custom windows don't move
        const query: HistoryQuery = { from: w.from.toISOString(), to: w.to.toISOString(), route: params.get('route') || null };
        this.store.dispatch(PositionHistoryActions.load({ query }));
        this.store.dispatch(
          PositionHistoryActions.loadSamples({ query, page: Math.max(0, (Number(params.get('page')) || 1) - 1) }),
        );
      });
  }

  private resolve(now: Date, params = this.params()) {
    return resolveWindow({ range: params.get('range'), from: params.get('from'), to: params.get('to') }, now);
  }

  protected setPreset(key: string): void {
    this.navigate({ range: key, from: null, to: null, page: null });
  }

  protected setCustom(which: 'from' | 'to', value: string): void {
    if (!value) return;
    const w = this.window();
    const from = which === 'from' ? value : toLocalInput(w.from);
    const to = which === 'to' ? value : toLocalInput(w.to);
    this.navigate({ range: null, from, to, page: null });
  }

  protected setRoute(route: string): void {
    this.navigate({ route: route || null, page: null });
  }

  protected setPage(page: number): void {
    this.navigate({ page: page > 0 ? page + 1 : null });
  }

  protected localInput(date: Date): string {
    return toLocalInput(date);
  }

  protected index(i: number): number {
    const p = this.samples()?.page;
    return (p ? p.number * p.size : 0) + i + 1;
  }

  private navigate(queryParams: Record<string, string | number | null>): void {
    void this.router.navigate([], { queryParams, queryParamsHandling: 'merge' });
  }
}

