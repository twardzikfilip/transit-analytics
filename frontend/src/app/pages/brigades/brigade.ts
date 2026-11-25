import { Component, ElementRef, computed, effect, inject } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { interval } from 'rxjs';
import { BrigadeDay } from '../../core/models/models';
import { injectBackLink } from '../../core/title/page-back';
import { AssumedTimetable } from '../../shared/assumed-timetable';
import { injectSetQuery } from '../../shared/url-query';
import { BrigadesActions } from '../../store/brigades/brigades.actions';
import { brigadesFeature } from '../../store/brigades/brigades.reducer';
import { TripRunsActions } from '../../store/trip-runs/trip-runs.actions';
import { tripRunsFeature } from '../../store/trip-runs/trip-runs.reducer';
import { listSpan } from '../trips/trip-layout';
import { REFRESH_MS, TripList } from '../trips/trip-list';
import { BrigadeDayChart } from './brigade-day-chart';
import { departureDelayText, share } from './brigade-format';
import { Skeleton } from '../../shared/skeleton/skeleton';
import { t } from '../../core/i18n/t';

/** A brigade's day keeps changing this long after its last trip (late buses, judging). */
const SETTLES_MS = 60 * 60_000;

/**
 * One brigade on one service day (/brigades/103-05?date=2026-11-18; without a date, today's): its trips as
 * scheduled and, under each, what actually ran: when, by which bus, how late, and what wasn't run; then
 * every trip as on the Trips page.
 */
@Component({
  selector: 'app-brigade',
  imports: [AssumedTimetable, Skeleton, BrigadeDayChart, TripList],
  templateUrl: './brigade.html',
  styleUrl: './brigades.scss',
})
export class BrigadePage {
  protected readonly t = t;
  private readonly store = inject(Store);
  private readonly router = inject(Router);
  private readonly navigate = injectSetQuery();
  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly route = inject(ActivatedRoute);
  private readonly params = toSignal(this.route.queryParamMap, { requireSync: true });
  private readonly path = toSignal(this.route.paramMap, { requireSync: true });

  protected readonly day = this.store.selectSignal(brigadesFeature.selectDay);
  protected readonly loadingDay = this.store.selectSignal(brigadesFeature.selectLoadingDay);

  protected readonly brigade = computed(() => this.path().get('brigade') ?? '');
  /** The operator the brigade was opened from (?agency=): another operator's is its feed. */
  protected readonly agency = computed(() => this.params().get('agency'));
  /** The day in the URL, else today's service day (as the backend says, with the brigade's day). */
  protected readonly date = computed(
    () => this.params().get('date') ?? this.shown()?.serviceDay ?? '',
  );
  /** The brigade's day shown: only the one asked for (not a previous one still on screen while it loads). */
  protected readonly shown = computed(() => {
    const d = this.day();
    return d && d.brigade === this.brigade() ? d : null;
  });
  private readonly loadedTrips = this.store.selectSignal(tripRunsFeature.selectDay);
  protected readonly loadingTrips = this.store.selectSignal(tripRunsFeature.selectLoadingDay);
  /** The same day's trips as on the Trips page; null while another brigade's or day's are in the store. */
  protected readonly tripDay = computed(() => {
    const trips = this.loadedTrips();
    const d = this.shown();
    return trips && d && trips.brigade === d.brigade && trips.serviceDay === d.serviceDay
      ? trips
      : null;
  });
  /** The list's time axis: the brigade's day, from its first trip to its last. */
  protected readonly tripSpan = computed(() => {
    const trips = this.tripDay()?.trips ?? [];
    return trips.length
      ? listSpan({ trips, shownFrom: trips[0].trip.departure, to: trips[0].trip.arrival })
      : null;
  });
  /** The trip open in the list (?trip=), marked on the chart. */
  protected readonly selected = computed(() => {
    const open = this.params().get('trip');
    const d = this.shown();
    const i = d?.trips.findIndex((trip) => `${trip.tripId}@${d.serviceDay}` === open) ?? -1;
    return i < 0 ? null : i;
  });

  protected readonly departureDelayText = departureDelayText;
  protected readonly share = share;

  constructor() {
    injectBackLink('nav.backToBrigades', () => this.back());
    let loaded = '';
    effect(() => {
      const brigade = this.brigade();
      const date = this.params().get('date');
      const key = `${brigade}|${date}`;
      if (brigade && key !== loaded) {
        loaded = key;
        this.load();
      }
    });
    // While the brigade is out, every minute, as the Trips page.
    interval(REFRESH_MS)
      .pipe(takeUntilDestroyed())
      .subscribe(() => {
        if (underWay(this.shown(), Date.now())) this.load();
      });
  }

  /** The brigade's day, and its trips for the list. */
  private load(): void {
    const brigade = this.brigade();
    const date = this.params().get('date');
    this.store.dispatch(BrigadesActions.loadDay({ brigade, date }));
    this.store.dispatch(TripRunsActions.loadDay({ brigade, date }));
  }

  protected setDate(date: string): void {
    if (date) this.navigate({ date, trip: null, map: null });
  }

  /** Back to every brigade of the same agency (the header's back button). */
  private back(): void {
    const agency = this.params().get('agency');
    void this.router.navigate(['/brigades'], { queryParams: agency ? { agency } : {} });
  }

  /** Chosen on the chart: opened in the list, or closed. */
  protected chooseTrip(i: number): void {
    const d = this.shown();
    if (!d) return;
    const key = `${d.trips[i].tripId}@${d.serviceDay}`;
    const opens = this.selected() !== i;
    this.navigate({ trip: opens ? key : null, map: null });
    if (!opens) return;
    setTimeout(() =>
      [...(this.host.nativeElement as HTMLElement).querySelectorAll('[data-trip]')]
        .find((row) => row.getAttribute('data-trip') === key)
        ?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' }),
    );
  }
}

/** Whether a brigade is out, or its last trip may not be judged yet: its day still changes. */
export function underWay(day: BrigadeDay | null, now: number): boolean {
  if (!day?.trips.length) return false;
  const first = Math.min(...day.trips.map((trip) => Date.parse(trip.departure)));
  const last = Math.max(...day.trips.map((trip) => Date.parse(trip.arrival)));
  return now >= first && now <= last + SETTLES_MS;
}
