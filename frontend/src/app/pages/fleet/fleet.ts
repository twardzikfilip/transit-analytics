import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { Store } from '@ngrx/store';
import { distinctUntilChanged, map } from 'rxjs';
import { VehicleUsage } from '../../core/models/models';
import { BarChart } from '../../shared/bar-chart/bar-chart';
import { localTime } from '../../shared/format/time';
import { injectSetQuery } from '../../shared/url-query';
import { TimeWindowPicker } from '../../shared/time-window/time-window-picker';
import {
  TimeWindow,
  readAgencies,
  readWindow,
  sameQuery,
  windowQuery,
} from '../../shared/time-window/time-window';
import { FleetActions } from '../../store/fleet/fleet.actions';
import { fleetFeature } from '../../store/fleet/fleet.reducer';
import {
  dailyBars,
  dayLabel,
  daysOutText,
  hoursAxis,
  isShortWindow,
  percent,
  searchVehicles,
  timelineRows,
  usageBars,
  vehicleName,
} from './fleet-format';
import { RunTimeline } from './run-timeline';

export const DEFAULT_PRESET = '1w';

interface FleetQuery {
  window: TimeWindow;
  agencies: string[];
  vehicle: number | null;
}

/**
 * Fleet: how the buses were used in a time window (in use, by model, the most and least used, breakdowns, every
 * bus's hours) and, for a bus found by number or model, its runs day by day and the abnormal trips it was part of.
 * The window, operators and chosen bus (?vehicle=id) are in the URL.
 */
@Component({
  selector: 'app-fleet',
  imports: [DatePipe, TimeWindowPicker, BarChart, RunTimeline],
  templateUrl: './fleet.html',
  styleUrl: './fleet.scss',
})
export class Fleet {
  private readonly store = inject(Store);
  private readonly navigate = injectSetQuery();

  protected readonly report = this.store.selectSignal(fleetFeature.selectReport);
  protected readonly loading = this.store.selectSignal(fleetFeature.selectLoading);
  protected readonly error = this.store.selectSignal(fleetFeature.selectError);
  protected readonly detail = this.store.selectSignal(fleetFeature.selectVehicle);
  protected readonly loadingVehicle = this.store.selectSignal(fleetFeature.selectLoadingVehicle);
  protected readonly vehicleError = this.store.selectSignal(fleetFeature.selectVehicleError);

  protected readonly defaultPreset = DEFAULT_PRESET;
  private readonly query$ = inject(ActivatedRoute).queryParamMap.pipe(
    map(
      (p): FleetQuery => ({
        window: readWindow(p, DEFAULT_PRESET),
        agencies: readAgencies(p),
        vehicle: Number(p.get('vehicle')) || null,
      }),
    ),
    distinctUntilChanged((a, b) => sameQuery(a, b) && a.vehicle === b.vehicle),
  );
  private readonly query = toSignal(this.query$, { requireSync: true });
  protected readonly selectedId = computed(() => this.query().vehicle);

  protected readonly search = signal('');
  protected readonly searchOpen = signal(false);
  protected readonly matches = computed(() => searchVehicles(this.report()?.usage ?? [], this.search()));

  protected readonly usageBars = computed(() => usageBars(this.report()?.usage ?? []));
  protected readonly inUseShare = computed(() => {
    const r = this.report();
    return r ? percent(r.inUse, r.fleet) : 0;
  });
  protected readonly modelMax = computed(() => Math.max(1, ...(this.report()?.models ?? []).map((m) => m.fleet)));
  protected readonly showAllModels = signal(false);
  protected readonly models = computed(() => {
    const models = this.report()?.models ?? [];
    return this.showAllModels() ? models : models.slice(0, 8);
  });

  protected readonly perRun = computed(() => {
    const d = this.detail();
    return d ? isShortWindow(d.from, d.to) : false;
  });
  protected readonly timeline = computed(() => {
    const d = this.detail();
    return d ? timelineRows(d, this.perRun()) : [];
  });
  protected readonly daily = computed(() => {
    const d = this.detail();
    return d ? dailyBars(d) : [];
  });

  protected readonly vehicleName = vehicleName;
  protected readonly daysOutText = daysOutText;
  protected readonly dayLabel = dayLabel;
  protected readonly hoursAxis = hoursAxis;
  protected readonly time = localTime;

  constructor() {
    let loaded: FleetQuery | null = null;
    this.query$.pipe(takeUntilDestroyed()).subscribe((q) => {
      if (!loaded || !sameQuery(loaded, q)) {
        this.store.dispatch(FleetActions.load({ window: windowQuery(q.window), agencies: q.agencies }));
      }
      if (q.vehicle && (!loaded || !sameQuery(loaded, q) || loaded.vehicle !== q.vehicle)) {
        this.store.dispatch(FleetActions.loadVehicle({ id: q.vehicle, window: windowQuery(q.window) }));
      } else if (!q.vehicle && loaded?.vehicle) {
        this.store.dispatch(FleetActions.clearVehicle());
      }
      loaded = q;
    });
  }

  /** Shows a bus's details below the search. */
  protected choose(id: number | string): void {
    this.search.set('');
    this.searchOpen.set(false);
    this.navigate({ vehicle: id });
  }

  protected clearVehicle(): void {
    this.navigate({ vehicle: null });
  }

  /** Enter picks the best match. */
  protected chooseFirst(): void {
    const first = this.matches()[0];
    if (first) this.choose(first.vehicle.id);
  }

  protected trackUsage(u: VehicleUsage): number {
    return u.vehicle.id;
  }
}
