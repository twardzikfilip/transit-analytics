import { Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Store } from '@ngrx/store';
import { LineStatsActions } from '../../store/line-stats/line-stats.actions';
import { lineStatsFeature } from '../../store/line-stats/line-stats.reducer';
import { RouteDetailActions } from '../../store/route-detail/route-detail.actions';
import { routeDetailFeature } from '../../store/route-detail/route-detail.reducer';
import { lineTable } from './format';
import { LineChooser } from './line-chooser';
import { periodsText, ranked, rankings } from './rankings';
import { Skeleton } from '../../shared/skeleton/skeleton';
import { t } from '../../core/i18n/t';
import { LineBadge } from '../../shared/line-kind/line-kinds';

/**
 * Routes: every line, a row per kind of line and a column per geometry; choosing one opens its page
 * ({@link RoutesPage}, /routes/route/103). Under them, eight tiles rank the lines (top 10) by length, speed,
 * regularity, delay and frequency, each from either end.
 */
@Component({
  selector: 'app-routes-overview',
  imports: [LineBadge, Skeleton, LineChooser, RouterLink],
  templateUrl: './routes-overview.html',
  styleUrl: './routes-overview.scss',
})
export class RoutesOverview {
  protected readonly t = t;
  private readonly store = inject(Store);
  private readonly router = inject(Router);

  protected readonly lines = this.store.selectSignal(routeDetailFeature.selectLines);
  protected readonly error = this.store.selectSignal(routeDetailFeature.selectError);
  protected readonly stats = this.store.selectSignal(lineStatsFeature.selectReport);
  protected readonly loadingStats = this.store.selectSignal(lineStatsFeature.selectLoading);
  protected readonly table = computed(() => lineTable(this.lines()));

  /** Which end each tile is ranked from (0: the first, e.g. Longest), by tile key. */
  private readonly sides = signal<Record<string, number>>({});

  /** The eight tiles, each with its top lines from the end chosen. */
  protected readonly tiles = computed(() => {
    const report = this.stats();
    if (!report) return [];
    const chosen = this.sides();
    return rankings(periodsText(report.peak), periodsText(report.offPeak), report.delayDays).map(
      (r) => {
        const side = chosen[r.key] ?? 0;
        return { ...r, side, lines: ranked(report.lines, r, r.sides[side]) };
      },
    );
  });

  constructor() {
    this.store.dispatch(RouteDetailActions.loadLines());
    this.store.dispatch(LineStatsActions.load());
  }

  protected setSide(key: string, side: number): void {
    this.sides.update((s) => ({ ...s, [key]: side }));
  }

  protected open(line: string): void {
    void this.router.navigate(['/routes/route', line]);
  }
}
