import { DatePipe } from '@angular/common';
import {
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { Store } from '@ngrx/store';
import * as L from 'leaflet';
import { timer } from 'rxjs';
import { BrigadeApi } from '../../core/api/api.services';
import { BrigadeSchedule, LivePosition } from '../../core/models/models';
import { LivePositionsActions } from '../../store/live-positions/live-positions.actions';
import { livePositionsFeature } from '../../store/live-positions/live-positions.reducer';
import { agencies, delayedOver, ofAgency, parseMinDelay } from './delay-filter';
import { escapeHtml } from '../../shared/format/html';
import { formatDelay, localTime } from '../../shared/format/time';
import { createBaseMap } from '../../shared/map/base-map';
import {
  DELAY_STOPS,
  NO_DELAY_COLOR,
  SUBTYPE_OUTLINES,
  delayColor,
  subtypeOutline,
} from '../../shared/palette';
import { remainingRuns } from './brigade-runs';

const REFRESH_MS = 5000;
/** A brigade's schedule is fetched again after this long (the popup shows what is left of the day). */
const SCHEDULE_MAX_AGE_MS = 10 * 60_000;

/** A brigade's schedule as the popup shows it: loading, loaded, or failed. */
type ScheduleState = { loadedAt: number; schedule: BrigadeSchedule | null; failed: boolean };

@Component({
  selector: 'app-live-map',
  imports: [DatePipe],
  templateUrl: './live-map.html',
  styleUrl: './live-map.scss',
})
export class LiveMap {
  private readonly store = inject(Store);
  private readonly destroyRef = inject(DestroyRef);
  private readonly mapEl = viewChild.required<ElementRef<HTMLDivElement>>('map');

  private map?: L.Map;
  private readonly mapReady = signal(false);
  private readonly markers = new Map<string, L.CircleMarker>();

  protected readonly positions = this.store.selectSignal(livePositionsFeature.selectAll);
  protected readonly count = this.store.selectSignal(livePositionsFeature.selectTotal);
  protected readonly lastUpdated = this.store.selectSignal(livePositionsFeature.selectLastUpdated);
  protected readonly error = this.store.selectSignal(livePositionsFeature.selectError);

  private readonly router = inject(Router);
  private readonly brigades = inject(BrigadeApi);
  private readonly params = toSignal(inject(ActivatedRoute).queryParamMap, { requireSync: true });
  /** "Delay over N minutes" filter, kept in the URL (?minDelay=5); null = show all vehicles. */
  protected readonly minDelay = computed(() => parseMinDelay(this.params().get('minDelay')));
  /** Operator filter, kept in the URL (?agency=BKM); '' = all operators. */
  protected readonly agency = computed(() => this.params().get('agency') ?? '');
  /** Operators on the map now, plus the selected one even when none of its vehicles is reporting. */
  protected readonly agencies = computed(() => agencies(this.positions(), this.agency()));
  protected readonly filtering = computed(() => this.minDelay() !== null || this.agency() !== '');
  /** The vehicles on the map. */
  protected readonly visible = computed(() =>
    delayedOver(ofAgency(this.positions(), this.agency()), this.minDelay()),
  );

  /** Legend. */
  protected readonly delayStops = DELAY_STOPS;
  protected readonly noDelayColor = NO_DELAY_COLOR;
  protected readonly outlines = SUBTYPE_OUTLINES;

  /** Brigade schedules fetched for popups, by brigade. */
  private readonly schedules = new Map<string, ScheduleState>();

  constructor() {
    afterNextRender(() => {
      this.map = createBaseMap(this.mapEl().nativeElement);
      this.mapReady.set(true);
    });
    this.destroyRef.onDestroy(() => this.map?.remove());

    // Poll through the store: the effect fetches, the reducer holds the latest positions.
    timer(0, REFRESH_MS)
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.store.dispatch(LivePositionsActions.load()));

    effect(() => {
      const positions = this.visible();
      if (this.mapReady()) this.render(positions);
    });
  }

  protected setAgency(agency: string): void {
    void this.router.navigate([], {
      queryParams: { agency: agency || null },
      queryParamsHandling: 'merge',
    });
  }

  /** Clears every filter: all vehicles on the map again. */
  protected resetFilters(): void {
    void this.router.navigate([], {
      queryParams: { minDelay: null, agency: null },
      queryParamsHandling: 'merge',
    });
  }

  /** Fetches a brigade's schedule for its popup, unless a recent one is at hand; the popup updates when it arrives. */
  private loadSchedule(brigade: string | null): void {
    if (!brigade) return;
    const known = this.schedules.get(brigade);
    if (known && Date.now() - known.loadedAt < SCHEDULE_MAX_AGE_MS) return;
    this.schedules.set(brigade, {
      loadedAt: Date.now(),
      schedule: known?.schedule ?? null,
      failed: false,
    });
    this.brigades
      .schedule(brigade)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (schedule) =>
          this.showSchedule(brigade, { loadedAt: Date.now(), schedule, failed: false }),
        // Try again the next time the popup opens.
        error: () => this.showSchedule(brigade, { loadedAt: 0, schedule: null, failed: true }),
      });
  }

  private showSchedule(brigade: string, state: ScheduleState): void {
    this.schedules.set(brigade, state);
    for (const p of this.visible()) {
      if (p.brigade === brigade) this.markers.get(p.vehicleNumber)?.setPopupContent(this.popup(p));
    }
  }

  protected setMinDelay(value: string): void {
    const minutes = parseMinDelay(value);
    // replaceUrl: typing a number shouldn't add a history entry per keystroke.
    void this.router.navigate([], {
      queryParams: { minDelay: minutes },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  private popup(p: LivePosition): string {
    return popup(p, p.brigade ? this.schedules.get(p.brigade) : undefined);
  }

  private render(positions: LivePosition[]): void {
    if (!this.map) return;
    const seen = new Set<string>();
    for (const p of positions) {
      seen.add(p.vehicleNumber);
      const latLng: L.LatLngExpression = [p.latitude, p.longitude];
      // Fill: delay, as on the Delays map. Outline: kind of line.
      const style = { color: subtypeOutline(p.subtype), fillColor: delayColor(p.delaySeconds) };
      const existing = this.markers.get(p.vehicleNumber);
      if (existing) {
        existing.setLatLng(latLng).setStyle(style).setPopupContent(this.popup(p));
        existing.off('popupopen').on('popupopen', () => this.loadSchedule(p.brigade));
      } else {
        const marker = L.circleMarker(latLng, { radius: 7, ...style, fillOpacity: 0.9, weight: 3 })
          .bindPopup(this.popup(p), { maxWidth: 320 })
          .bindTooltip(escapeHtml(p.routeName ?? p.vehicleNumber), {
            direction: 'top',
            offset: [0, -6],
          })
          .on('popupopen', () => this.loadSchedule(p.brigade))
          .addTo(this.map);
        this.markers.set(p.vehicleNumber, marker);
      }
    }
    for (const [id, marker] of this.markers) {
      if (!seen.has(id)) {
        marker.remove();
        this.markers.delete(id);
      }
    }
  }
}

function popup(p: LivePosition, schedule: ScheduleState | undefined): string {
  const line = [p.routeName, p.headsign]
    .filter(Boolean)
    .map((v) => escapeHtml(v!))
    .join(' → ');
  const vehicle = [p.vehicleNumber, p.model, p.agency]
    .filter(Boolean)
    .map((v) => escapeHtml(v!))
    .join(' · ');
  const next = p.upcomingStops[0];
  const nextStop = next
    ? `<br>Next: ${escapeHtml(next.name)}${next.scheduledArrival ? ` (${localTime(next.scheduledArrival)})` : ''}`
    : '';
  const seen = p.timestamp ? `<br><span class="muted">as of ${localTime(p.timestamp)}</span>` : '';
  return (
    `<strong>${line || escapeHtml(p.vehicleNumber)}</strong><br>${vehicle}<br>${delay(p.delaySeconds)}${nextStop}${seen}` +
    brigadeSection(p.brigade, schedule)
  );
}

/** The brigade and what is left of its day: the run under way, then the ones to come. */
function brigadeSection(brigade: string | null, state: ScheduleState | undefined): string {
  if (!brigade) return '';
  const title = `<div class="brigade"><strong>Brigade ${escapeHtml(brigade)}</strong>`;
  if (!state || (!state.schedule && !state.failed))
    return `${title}<br><span class="muted">Loading schedule…</span></div>`;
  if (!state.schedule) return `${title}<br><span class="muted">Schedule unavailable</span></div>`;
  if (!state.schedule.runs.length)
    return `${title}<br><span class="muted">Not in today's timetable</span></div>`;
  const runs = remainingRuns(state.schedule.runs, new Date());
  if (!runs.length) return `${title}<br><span class="muted">No more runs today</span></div>`;
  const rows = runs
    .map(
      ({ run, now }) =>
        `<li${now ? ' class="now"' : ''}><span class="t">${localTime(run.departure)}–${localTime(run.arrival)}</span> ` +
        `<strong>${escapeHtml(run.line)}</strong> ${escapeHtml(run.from)} → ${escapeHtml(run.to)}</li>`,
    )
    .join('');
  return `${title} <span class="muted">· rest of today</span><ol class="runs">${rows}</ol></div>`;
}

function delay(seconds: number | null): string {
  if (seconds === null) return 'Delay unknown';
  if (Math.abs(seconds) < 30) return 'On time';
  return `${formatDelay(Math.abs(seconds))} ${seconds > 0 ? 'late' : 'early'}`;
}
