import { Component, computed, input, output } from '@angular/core';
import { RailDay } from '../../core/models/models';
import { t } from '../../core/i18n/t';
import { LocalDatePipe } from '../../shared/i18n/locale-pipes';
import { ColoredRoute } from './rail-map';

/** The routes with their trains per day, from and to the city: Polregio's, or all. */
@Component({
  selector: 'app-route-table',
  imports: [LocalDatePipe],
  templateUrl: './route-table.html',
  styleUrl: './route-table.scss',
})
export class RouteTable {
  protected readonly t = t;
  readonly routes = input.required<ColoredRoute[]>();
  readonly days = input.required<RailDay[]>();
  readonly trains = input.required<'polregio' | 'all'>();
  /** Whether each route's stations are listed. */
  readonly stations = input(false);
  /** The route chosen. */
  readonly chosen = input<string | null>(null);
  readonly choose = output<string>();
  /** The route pointed at. */
  readonly hover = output<string | null>();

  protected readonly rows = computed(() =>
    this.routes().map((route) => ({
      ...route,
      stationNames: route.stations.map((s) => s.name).join(' – '),
      trains: route[this.trains()],
    })),
  );

  /** All routes together, per day. */
  protected readonly totals = computed(() =>
    this.days().map((_, i) =>
      this.rows().reduce(
        (sum, r) => ({
          fromCity: sum.fromCity + r.trains.departures[i].fromCity,
          toCity: sum.toCity + r.trains.departures[i].toCity,
        }),
        { fromCity: 0, toCity: 0 },
      ),
    ),
  );
}
