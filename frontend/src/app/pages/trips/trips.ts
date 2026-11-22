import { Component, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Store } from '@ngrx/store';
import { Subject, debounceTime, distinctUntilChanged } from 'rxjs';
import { routeColor } from '../../core/gtfs';
import { Pager, rowNumber } from '../../shared/pager/pager';
import { listQuery } from '../../shared/list-query';
import { RoutesActions } from '../../store/routes/routes.actions';
import { routesFeature } from '../../store/routes/routes.reducer';
import { TripsActions } from '../../store/trips/trips.actions';
import { tripsFeature } from '../../store/trips/trips.reducer';
import { Skeleton } from '../../shared/skeleton/skeleton';
import { t } from '../../core/i18n/t';

@Component({
  selector: 'app-trips',
  imports: [Skeleton, RouterLink, Pager],
  templateUrl: './trips.html',
})
export class Trips {
  protected readonly t = t;
  private readonly store = inject(Store);

  protected readonly trips = this.store.selectSignal(tripsFeature.selectAll);
  protected readonly page = this.store.selectSignal(tripsFeature.selectPage);
  protected readonly loading = this.store.selectSignal(tripsFeature.selectLoading);
  /** For the route picker. */
  protected readonly routes = this.store.selectSignal(routesFeature.selectAll);

  protected readonly query = listQuery({
    filters: ['routeId', 'direction', 'serviceId', 'q'],
    size: 50,
    sort: () => 'id',
    onChange: (query) => this.store.dispatch(TripsActions.load({ query })),
  });

  protected readonly routeColor = routeColor;
  private readonly search$ = new Subject<string>();

  constructor() {
    if (this.routes().length === 0) this.store.dispatch(RoutesActions.load());
    this.search$
      .pipe(debounceTime(300), distinctUntilChanged(), takeUntilDestroyed())
      .subscribe((q) => this.query.setFilters({ q: q.trim() }));
  }

  protected onSearch(value: string): void {
    this.search$.next(value);
  }

  protected index(i: number): number {
    return rowNumber(this.page(), i);
  }
}
