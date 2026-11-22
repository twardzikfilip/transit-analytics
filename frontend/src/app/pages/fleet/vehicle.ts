import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { combineLatest, distinctUntilChanged, map } from 'rxjs';
import { VehicleIncident } from '../../core/models/models';
import { injectBackLink } from '../../core/title/page-back';
import { BarChart } from '../../shared/bar-chart/bar-chart';
import { IncidentStory } from '../../shared/incident-timeline/incident-story';
import { localTime } from '../../shared/format/time';
import { TimeWindowPicker } from '../../shared/time-window/time-window-picker';
import { windowQuery } from '../../shared/time-window/time-window';
import { FleetActions } from '../../store/fleet/fleet.actions';
import { fleetFeature } from '../../store/fleet/fleet.reducer';
import { DEFAULT_PRESET, fleetQuery, loadReport, openVehicle } from './fleet';
import { dailyBars, dayLabel, hoursAxis, isShortWindow, timelineRows } from './fleet-format';
import { RunTimeline } from './run-timeline';
import { VehicleSearch } from './vehicle-search';
import { Skeleton } from '../../shared/skeleton/skeleton';
import { LocalDatePipe } from '../../shared/i18n/locale-pipes';
import { t, tn } from '../../core/i18n/t';
import { decimal } from '../../shared/format/number';
import { phrasesText } from '../../shared/i18n/phrases';

/**
 * One bus (/vehicles/7, its id): its runs day by day, its hours per day and the abnormal trips it was part of,
 * in the time window in the URL. Its title, "Vehicle 230", comes from the route (see fleet.routes.ts).
 */
@Component({
  selector: 'app-fleet-vehicle',
  imports: [
    Skeleton,
    LocalDatePipe,
    TimeWindowPicker,
    BarChart,
    RunTimeline,
    IncidentStory,
    VehicleSearch,
  ],
  templateUrl: './vehicle.html',
  styleUrl: './fleet.scss',
})
export class VehiclePage {
  protected readonly t = t;
  protected readonly phrasesText = phrasesText;
  protected readonly decimal = decimal;
  protected readonly tn = tn;
  private readonly store = inject(Store);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  /** The fleet's report, for the search. */
  protected readonly report = this.store.selectSignal(fleetFeature.selectReport);
  protected readonly loading = this.store.selectSignal(fleetFeature.selectLoading);
  protected readonly detail = this.store.selectSignal(fleetFeature.selectVehicle);
  protected readonly loadingVehicle = this.store.selectSignal(fleetFeature.selectLoadingVehicle);

  protected readonly defaultPreset = DEFAULT_PRESET;
  private readonly id$ = this.route.paramMap.pipe(
    map((p) => Number(p.get('id'))),
    distinctUntilChanged(),
  );
  protected readonly selectedId = toSignal(this.id$, { requireSync: true });
  /** The chosen bus's details: only its own (not a previous bus's while it loads). */
  protected readonly shown = computed(() => {
    const d = this.detail();
    return d && d.vehicle.id === this.selectedId() ? d : null;
  });

  protected readonly perRun = computed(() => {
    const d = this.shown();
    return d ? isShortWindow(d.from, d.to) : false;
  });
  protected readonly timeline = computed(() => {
    const d = this.shown();
    return d ? timelineRows(d, this.perRun()) : [];
  });
  protected readonly daily = computed(() => {
    const d = this.shown();
    return d ? dailyBars(d) : [];
  });

  protected readonly dayLabel = dayLabel;
  protected readonly hoursAxis = hoursAxis;
  protected readonly time = localTime;
  /** Breakdowns whose step-by-step story is folded away (all are open at first). */
  private readonly folded = signal<ReadonlySet<string>>(new Set());

  constructor() {
    injectBackLink('nav.backToFleet', () => this.back());
    const state = this.store.selectSignal(fleetFeature.selectFleetState);
    combineLatest([fleetQuery(this.route), this.id$])
      .pipe(takeUntilDestroyed())
      .subscribe(([q, id]) => {
        // The fleet's buses, for the search: the report already loaded for this window when there is one.
        loadReport(this.store, state, q);
        this.store.dispatch(FleetActions.loadVehicle({ id, window: windowQuery(q.window) }));
      });
  }

  protected storyOpen(i: VehicleIncident): boolean {
    return i.story.length > 0 && !this.folded().has(i.scheduledDeparture + i.kind);
  }

  protected toggleStory(i: VehicleIncident): void {
    const folded = new Set(this.folded());
    const key = i.scheduledDeparture + i.kind;
    if (!folded.delete(key)) folded.add(key);
    this.folded.set(folded);
  }

  protected choose(id: number): void {
    openVehicle(this.router, id);
  }

  /** Back to the fleet overview, in the same window (the header's back button). */
  private back(): void {
    void this.router.navigate(['/vehicles'], { queryParamsHandling: 'preserve' });
  }
}
