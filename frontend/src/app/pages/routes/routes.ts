import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Store } from '@ngrx/store';
import { routeColor, routeTypeLabel } from '../../core/gtfs';
import { RoutesActions } from '../../store/routes/routes.actions';
import { routesFeature } from '../../store/routes/routes.reducer';

@Component({
  selector: 'app-routes',
  imports: [RouterLink],
  templateUrl: './routes.html',
})
export class Routes {
  private readonly store = inject(Store);

  protected readonly routes = this.store.selectSignal(routesFeature.selectAll);
  protected readonly loading = this.store.selectSignal(routesFeature.selectLoading);
  protected readonly error = this.store.selectSignal(routesFeature.selectError);

  protected readonly search = signal('');
  protected readonly filtered = computed(() => {
    const q = this.search().trim().toLowerCase();
    return q
      ? this.routes().filter((r) => [r.name, r.longName, r.description].some((v) => v?.toLowerCase().includes(q)))
      : this.routes();
  });

  protected readonly routeTypeLabel = routeTypeLabel;
  protected readonly routeColor = routeColor;

  constructor() {
    this.store.dispatch(RoutesActions.load());
  }
}
