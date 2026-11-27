import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { Store } from '@ngrx/store';
import { map } from 'rxjs';
import { t } from '../../core/i18n/t';
import { VARIANT_COLORS } from '../../shared/palette';
import { Skeleton } from '../../shared/skeleton/skeleton';
import { injectSetQuery } from '../../shared/url-query';
import { CityRailActions } from '../../store/city-rail/city-rail.actions';
import { cityRailFeature } from '../../store/city-rail/city-rail.reducer';
import { RailBusTable } from './rail-bus-table';
import { RailMap } from './rail-map';
import { RouteTable } from './route-table';

/**
 * City rail: the railways in and around the city on a map, and the routes on them, each in its colour with its
 * trains per day both ways: Polregio's, and together with PKP Intercity's. The route chosen (?route=Wasilków) is highlighted on the map.
 * Last, train against bus between the stations in the city (loaded on its own, as it takes longer).
 */
@Component({
  selector: 'app-city-rail',
  imports: [RailBusTable, RailMap, RouteTable, Skeleton],
  templateUrl: './city-rail.html',
  styleUrl: './city-rail.scss',
})
export class CityRail {
  protected readonly t = t;
  private readonly store = inject(Store);
  private readonly setQuery = injectSetQuery();

  protected readonly report = this.store.selectSignal(cityRailFeature.selectReport);
  protected readonly loading = this.store.selectSignal(cityRailFeature.selectLoading);
  protected readonly comparison = this.store.selectSignal(cityRailFeature.selectComparison);
  protected readonly comparisonLoading = this.store.selectSignal(
    cityRailFeature.selectComparisonLoading,
  );

  protected readonly routes = computed(() =>
    (this.report()?.routes ?? []).map((route, i) => ({
      ...route,
      color: VARIANT_COLORS[i % VARIANT_COLORS.length],
      name: `${route.stations[0].name} – ${route.id}`,
    })),
  );

  /** The route chosen, from the URL. */
  protected readonly chosen = toSignal(
    inject(ActivatedRoute).queryParamMap.pipe(map((p) => p.get('route'))),
    { requireSync: true },
  );
  /** The route pointed at in the list. */
  protected readonly hovered = signal<string | null>(null);
  protected readonly highlighted = computed(() => this.hovered() ?? this.chosen());

  constructor() {
    this.store.dispatch(CityRailActions.load());
    this.store.dispatch(CityRailActions.loadComparison());
  }

  /** Chooses a route, or no longer, if it was chosen. */
  protected choose(id: string): void {
    this.setQuery({ route: this.chosen() === id ? null : id });
  }
}
