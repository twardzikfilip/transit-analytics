import { Component, computed, inject, input } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { catchError, of, startWith, switchMap } from 'rxjs';
import { TripRunApi } from '../../core/api/api.services';
import { TripRun, TripRunDetail } from '../../core/models/models';
import { RouteBadge } from '../../shared/route-kind/route-kinds';
import { phrasesText } from '../../shared/i18n/phrases';
import { formatDelay, localTime } from '../../shared/format/time';
import { CHANGE_COLOR, DELAY_STOPS, NOT_RUN_COLOR, NOT_SEEN_COLOR } from '../../shared/palette';
import { injectSetQuery } from '../../shared/url-query';
import { busesText, departureDelayText, kindLabel, PROBLEMS } from '../brigades/brigade-format';
import { TripDetail } from './trip-detail';
import { TripMap } from './trip-map';
import { TripAxis, TripLane, TripLanePatterns } from './trip-lane';
import { delaySummary, longestHalts, tripEvents } from './trip-layout';
import { t } from '../../core/i18n/t';

/** How often a list that still changes is loaded again. */
export const REFRESH_MS = 60_000;

/** A trip's key in the URL (?trip=…): its id and service day, as trip ids repeat across days. */
export function tripKey(r: TripRun): string {
  return `${r.trip.tripId}@${r.serviceDay}`;
}

/**
 * A list of trips on one time axis, each with what ran: the scheduled bar over the actual one, with its legend. Open
 * a trip for its delay and speed over the whole trip, its map and what happened. Which trip is open, and its map, are
 * in the URL (?trip=, ?map=1). Used by the Trips page and a brigade's page.
 */
@Component({
  selector: 'app-trip-list',
  imports: [RouterLink, RouteBadge, TripLane, TripAxis, TripLanePatterns, TripDetail, TripMap],
  templateUrl: './trip-list.html',
  styleUrl: './trip-list.scss',
})
export class TripList {
  readonly trips = input.required<TripRun[]>();
  /** The shared time axis (epoch ms); null when there are no trips. */
  readonly span = input.required<[number, number] | null>();
  readonly loading = input(false);
  /** What to say when there are no trips. */
  readonly empty = input('');
  /** Whether an open trip links to its brigade's day (not on that page itself). */
  readonly brigadeLink = input(true);

  protected readonly t = t;
  private readonly setQuery = injectSetQuery();
  private readonly params = toSignal(inject(ActivatedRoute).queryParamMap, { requireSync: true });
  protected readonly open = computed(() => this.params().get('trip'));
  private readonly api = inject(TripRunApi);
  /** The open trip, when it is on the page shown. */
  private readonly openRun = computed(
    () => this.trips().find((r) => tripKey(r) === this.open()) ?? null,
  );
  /** The trip whose detail is shown. */
  private shownKey: string | null = null;
  /**
   * The open trip in detail (every report, its stops with how long the bus stood): undefined while it loads, null
   * when none (no trip open, or the request failed: a toast). Asked for again with each refresh of the list, keeping
   * what is shown until the answer comes, so a trip under way keeps up.
   */
  protected readonly detail = toSignal(
    toObservable(this.openRun).pipe(
      switchMap((run) => {
        if (!run) {
          this.shownKey = null;
          return of(null);
        }
        const opened = tripKey(run) !== this.shownKey;
        this.shownKey = tripKey(run);
        const answer = this.api.detail(run).pipe(catchError(() => of(null)));
        return opened ? answer.pipe(startWith(undefined)) : answer;
      }),
    ),
    { initialValue: null as TripRunDetail | null | undefined },
  );
  /** The open trip's map (?map=1). */
  protected readonly mapShown = computed(() => this.params().get('map') === '1');
  /** Legend. */
  protected readonly delayStops = DELAY_STOPS;
  protected readonly notRun = NOT_RUN_COLOR;
  protected readonly notSeen = NOT_SEEN_COLOR;
  protected readonly change = CHANGE_COLOR;

  protected readonly key = tripKey;
  protected readonly time = localTime;
  protected readonly kindLabel = kindLabel;
  protected readonly busesText = busesText;
  protected readonly delayText = departureDelayText;
  protected readonly delaySummary = delaySummary;
  protected readonly events = tripEvents;
  protected readonly halts = longestHalts;
  protected readonly minutes = formatDelay;
  protected readonly phrasesText = phrasesText;

  /** Opens a trip's details, or closes them when open. */
  protected toggle(r: TripRun): void {
    const key = tripKey(r);
    this.setQuery({ trip: this.open() === key ? null : key, map: null });
  }

  protected toggleMap(): void {
    this.setQuery({ map: this.mapShown() ? null : '1' });
  }

  protected problem(r: TripRun): boolean {
    return PROBLEMS.includes(r.trip.kind);
  }
}
