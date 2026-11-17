import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { distinctUntilChanged, map } from 'rxjs';
import { StatsAgency, StatsWindow, VehicleIncident } from '../../core/models/models';
import { formatDelay, localTime } from '../../shared/format/time';
import { VehicleStatsActions } from '../../store/vehicle-stats/vehicle-stats.actions';
import { vehicleStatsFeature } from '../../store/vehicle-stats/vehicle-stats.reducer';
import { AgencyPicker } from './agency-picker';
import { UsageTable } from './usage-table';

export const WINDOWS: { key: StatsWindow; label: string }[] = [
  { key: 'day', label: 'Last 24 hours' },
  { key: 'week', label: 'Last 7 days' },
  { key: 'month', label: 'Last 30 days' },
];
const DEFAULT_WINDOW: StatsWindow = 'week';
/** Rows of the long lists shown before "Show all". */
export const FIRST_ROWS = 20;

/** The time window from the URL (?window=day|week|month), else the default. */
export function parseWindow(value: string | null): StatsWindow {
  return WINDOWS.some((w) => w.key === value) ? (value as StatsWindow) : DEFAULT_WINDOW;
}

/** "to line 10 (010-04) at 06:40" or "not seen again that day". */
export function whatHappened(i: VehicleIncident): string {
  if (i.kind === 'switched' && i.nextSeen) {
    return `to line ${i.nextLine}${i.nextBrigade ? ` (${i.nextBrigade})` : ''} at ${localTime(i.nextSeen)}`;
  }
  return 'not seen again that day';
}

/** "before Rynek, 2:11 min late": where the vehicle was heading at its last report, and its delay. */
export function lastSeenNote(i: VehicleIncident): string {
  const parts = [i.lastStop ? `before ${i.lastStop}` : null];
  if (i.delaySeconds) {
    parts.push(
      `${formatDelay(Math.abs(i.delaySeconds))} min ${i.delaySeconds > 0 ? 'late' : 'early'}`,
    );
  }
  return parts.filter(Boolean).join(', ');
}

/**
 * Vehicles in detail: the most and least used vehicles, vehicles serving many or few lines, vehicles on several lines
 * in one day, and vehicles that left their brigade while it was scheduled to run. The time window is in the URL.
 */
@Component({
  selector: 'app-vehicle-stats',
  imports: [AgencyPicker, DatePipe, UsageTable],
  templateUrl: './vehicle-stats.html',
  styleUrl: './vehicle-stats.scss',
})
export class VehicleStats {
  private readonly store = inject(Store);
  private readonly router = inject(Router);

  protected readonly report = this.store.selectSignal(vehicleStatsFeature.selectReport);
  protected readonly loading = this.store.selectSignal(vehicleStatsFeature.selectLoading);
  protected readonly error = this.store.selectSignal(vehicleStatsFeature.selectError);

  protected readonly windows = WINDOWS;
  /** The window and operators in the URL (?window=month&agency=BKM&agency=…); a change loads their report. */
  private readonly query$ = inject(ActivatedRoute).queryParamMap.pipe(
    map((p) => ({ window: parseWindow(p.get('window')), agencies: p.getAll('agency') })),
    distinctUntilChanged((a, b) => JSON.stringify(a) === JSON.stringify(b)),
  );
  private readonly query = toSignal(this.query$, { requireSync: true });
  protected readonly window = computed(() => this.query().window);
  protected readonly agencies = computed(() => this.query().agencies);
  /** The window's operators, and any chosen one without vehicles in it, so it can still be unticked. */
  protected readonly agencyOptions = computed((): StatsAgency[] => {
    const options = this.report()?.agencies ?? [];
    const missing = this.agencies().filter((a) => !options.some((o) => o.name === a));
    return [...options, ...missing.map((name) => ({ name, vehicles: 0 }))];
  });

  protected readonly showAllDays = signal(false);
  protected readonly showAllIncidents = signal(false);
  protected readonly lineDays = computed(() =>
    this.firstRows(this.report()?.multiLineDays ?? [], this.showAllDays()),
  );
  protected readonly incidents = computed(() =>
    this.firstRows(this.report()?.incidents ?? [], this.showAllIncidents()),
  );
  protected readonly firstRowsCount = FIRST_ROWS;

  protected readonly time = localTime;
  protected readonly whatHappened = whatHappened;
  protected readonly lastSeenNote = lastSeenNote;

  constructor() {
    this.query$.pipe(takeUntilDestroyed()).subscribe(({ window, agencies }) => {
      this.showAllDays.set(false);
      this.showAllIncidents.set(false);
      this.store.dispatch(VehicleStatsActions.load({ window, agencies }));
    });
  }

  protected setWindow(window: StatsWindow): void {
    void this.router.navigate([], {
      queryParams: { window: window === DEFAULT_WINDOW ? null : window },
      queryParamsHandling: 'merge',
    });
  }

  /** Keeps the operators whose vehicles the tables include; none for all. */
  protected setAgencies(agencies: string[]): void {
    void this.router.navigate([], {
      queryParams: { agency: agencies.length ? agencies : null },
      queryParamsHandling: 'merge',
    });
  }

  private firstRows<T>(rows: T[], all: boolean): T[] {
    return all ? rows : rows.slice(0, FIRST_ROWS);
  }
}
