import { Component, computed, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { distinctUntilChanged, map } from 'rxjs';
import { BarChart } from '../../shared/bar-chart/bar-chart';
import { TimeWindowPicker } from '../../shared/time-window/time-window-picker';
import {
  TimeWindow,
  queryKey,
  readAgencies,
  readWindow,
  sameQuery,
  windowQuery,
} from '../../shared/time-window/time-window';
import { FleetActions } from '../../store/fleet/fleet.actions';
import { FleetState, fleetFeature, hasFreshReport } from '../../store/fleet/fleet.reducer';
import { daysOutText, hoursAxis, wholePercent, usageBars, vehicleClassLabel } from './fleet-format';
import { CountBars } from './count-bars';
import { VehicleSearch } from './vehicle-search';
import { Skeleton } from '../../shared/skeleton/skeleton';
import { LocalDatePipe } from '../../shared/i18n/locale-pipes';
import { t } from '../../core/i18n/t';
import { decimal } from '../../shared/format/number';

export const DEFAULT_PRESET = '1w';

export interface FleetQuery {
  window: TimeWindow;
  agencies: string[];
}

/** The time window and operators in the URL, each time they change. */
export function fleetQuery(route: ActivatedRoute) {
  return route.queryParamMap.pipe(
    map((p): FleetQuery => ({ window: readWindow(p, DEFAULT_PRESET), agencies: readAgencies(p) })),
    distinctUntilChanged(sameQuery),
  );
}

/**
 * Loads the fleet report for the query, unless the store already has it (or is loading it) and it is fresh: going
 * from the overview to a bus, from bus to bus and back asks the backend once per window.
 */
export function loadReport(store: Store, state: () => FleetState | undefined, q: FleetQuery): void {
  const key = queryKey(q.window, q.agencies);
  if (hasFreshReport(state(), key)) return;
  store.dispatch(FleetActions.load({ window: windowQuery(q.window), agencies: q.agencies, key }));
}

/** Opens a bus's page (/vehicles/7), keeping the window and operators. */
export function openVehicle(router: Router, id: number): void {
  void router.navigate(['/vehicles', id], { queryParamsHandling: 'preserve' });
}

/**
 * Fleet overview: how the buses were used in a time window (in use, by model, the most and least used, breakdowns,
 * every bus's hours). A bus found by number or model, or clicked, opens its own page ({@link VehiclePage}). The
 * window and operators are in the URL.
 */
@Component({
  selector: 'app-fleet',
  imports: [Skeleton, LocalDatePipe, TimeWindowPicker, BarChart, CountBars, VehicleSearch],
  templateUrl: './fleet.html',
  styleUrl: './fleet.scss',
})
export class Fleet {
  protected readonly t = t;
  protected readonly decimal = decimal;
  private readonly store = inject(Store);
  private readonly router = inject(Router);

  protected readonly report = this.store.selectSignal(fleetFeature.selectReport);
  protected readonly loading = this.store.selectSignal(fleetFeature.selectLoading);

  protected readonly defaultPreset = DEFAULT_PRESET;
  private readonly query$ = fleetQuery(inject(ActivatedRoute));

  protected readonly usageBars = computed(() => usageBars(this.report()?.usage ?? []));
  protected readonly inUseShare = computed(() => {
    const r = this.report();
    return r ? wholePercent(r.inUse, r.fleet) : 0;
  });
  protected readonly classLabel = vehicleClassLabel;
  protected readonly daysOutText = daysOutText;
  protected readonly hoursAxis = hoursAxis;

  constructor() {
    const state = this.store.selectSignal(fleetFeature.selectFleetState);
    this.query$.pipe(takeUntilDestroyed()).subscribe((q) => loadReport(this.store, state, q));
  }

  protected choose(id: number | string): void {
    openVehicle(this.router, Number(id));
  }
}
