import { Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Store } from '@ngrx/store';
import { RouteStatsActions } from '../../store/route-stats/route-stats.actions';
import { routeStatsFeature } from '../../store/route-stats/route-stats.reducer';
import { RouteDetailActions } from '../../store/route-detail/route-detail.actions';
import { routeDetailFeature } from '../../store/route-detail/route-detail.reducer';
import { routeTable } from './format';
import { RouteChooser } from './route-chooser';
import { periodsText, ranked, rankings } from './rankings';
import { Skeleton } from '../../shared/skeleton/skeleton';
import { t } from '../../core/i18n/t';
import { RouteBadge } from '../../shared/route-kind/route-kinds';

/**
 * Routes: every route, a row per kind of route and a column per geometry; choosing one opens its page
 * ({@link RoutesPage}, /routes/route/103). Under them, eight tiles rank the routes (top 10) by length, speed,
 * regularity, delay and frequency, each from either end.
 */
@Component({
  selector: 'app-routes-overview',
  imports: [RouteBadge, Skeleton, RouteChooser, RouterLink],
  templateUrl: './routes-overview.html',
  styleUrl: './routes-overview.scss',
})
export class RoutesOverview {
  protected readonly t = t;
  private readonly store = inject(Store);
  private readonly router = inject(Router);

  protected readonly routes = this.store.selectSignal(routeDetailFeature.selectRoutes);
  protected readonly error = this.store.selectSignal(routeDetailFeature.selectError);
  protected readonly stats = this.store.selectSignal(routeStatsFeature.selectReport);
  protected readonly loadingStats = this.store.selectSignal(routeStatsFeature.selectLoading);
  protected readonly table = computed(() => routeTable(this.routes()));

  /** Which end each tile is ranked from (0: the first, e.g. Longest), by tile key. */
  private readonly sides = signal<Record<string, number>>({});

  /** The eight tiles, each with its top routes from the end chosen. */
  protected readonly tiles = computed(() => {
    const report = this.stats();
    if (!report) return [];
    const chosen = this.sides();
    return rankings(periodsText(report.peak), periodsText(report.offPeak), report.delayDays).map(
      (r) => {
        const side = chosen[r.key] ?? 0;
        return { ...r, side, routes: ranked(report.routes, r, r.sides[side]) };
      },
    );
  });

  constructor() {
    this.store.dispatch(RouteDetailActions.loadRoutes());
    this.store.dispatch(RouteStatsActions.load());
  }

  protected setSide(key: string, side: number): void {
    this.sides.update((s) => ({ ...s, [key]: side }));
  }

  protected open(route: string): void {
    void this.router.navigate(['/routes/route', route]);
  }
}
