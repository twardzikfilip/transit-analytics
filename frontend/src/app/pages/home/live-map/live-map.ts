import { DatePipe } from '@angular/common';
import { Component, ElementRef, computed, effect, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { injectSetQuery } from '../../../shared/url-query';
import { Store } from '@ngrx/store';
import * as L from 'leaflet';
import { timer } from 'rxjs';
import { LivePosition } from '../../../core/models/models';
import { LivePositionsActions } from '../../../store/live-positions/live-positions.actions';
import { livePositionsFeature } from '../../../store/live-positions/live-positions.reducer';
import { agencies, delayedOver, ofAgency, parseMinDelay } from './delay-filter';
import { escapeHtml } from '../../../shared/format/html';
import { formatDelay, localTime } from '../../../shared/format/time';
import { leafletHost } from '../../../shared/map/leaflet-host';
import {
  BOUNDARY_STYLES,
  BUS_LANE_STYLE,
  DELAY_STOPS,
  NO_DELAY_COLOR,
  SUBTYPE_OUTLINES,
  delayColor,
  subtypeOutline,
} from '../../../shared/palette';
import {
  BrigadeSchedules,
  ScheduleState,
  brigadeSection,
} from '../../../shared/brigade/brigade-schedules';
import { injectBusLanes } from '../../../shared/map/bus-lanes';
import { injectCityBoundaries } from '../../../shared/map/city-boundaries';

const REFRESH_MS = 5000;
/**
 * Where every vehicle is now and how late it is, refreshed every 5 s from the live feed. It doesn't depend on any
 * time window: only on its own filters.
 */
@Component({
  selector: 'app-live-map',
  imports: [DatePipe],
  templateUrl: './live-map.html',
  styleUrl: './live-map.scss',
})
export class LiveMap {
  private readonly store = inject(Store);
  private readonly mapEl = viewChild.required<ElementRef<HTMLDivElement>>('map');

  private readonly host = leafletHost(() => this.mapEl().nativeElement, {
    setUp: (map) => {
      this.drawBoundaries(map);
      this.drawBusLanes(map);
    },
  });
  private readonly markers = new Map<string, L.CircleMarker>();

  protected readonly positions = this.store.selectSignal(livePositionsFeature.selectAll);
  protected readonly count = this.store.selectSignal(livePositionsFeature.selectTotal);
  protected readonly lastUpdated = this.store.selectSignal(livePositionsFeature.selectLastUpdated);
  protected readonly error = this.store.selectSignal(livePositionsFeature.selectError);

  private readonly setQuery = injectSetQuery();
  private readonly params = toSignal(inject(ActivatedRoute).queryParamMap, { requireSync: true });
  /**
   * The map's own filters, kept in the URL under their own names, so the time window and operator of the recorded
   * positions below it never change the map: "delay over N minutes" (?mapMinDelay=5; null = all vehicles) and the
   * operator (?mapAgency=BKM; '' = all operators).
   */
  protected readonly minDelay = computed(() => parseMinDelay(this.params().get('mapMinDelay')));
  protected readonly agency = computed(() => this.params().get('mapAgency') ?? '');
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
  protected readonly boundaries = [
    BOUNDARY_STYLES.city,
    BOUNDARY_STYLES.centre,
    { color: BUS_LANE_STYLE.color, dashArray: undefined, label: BUS_LANE_STYLE.label },
  ];

  /** Brigade schedules for popups; an arriving one redraws its vehicles' popups. */
  private readonly schedules = new BrigadeSchedules((brigade) => this.showSchedule(brigade));
  private readonly drawBoundaries = injectCityBoundaries();
  private readonly drawBusLanes = injectBusLanes();

  constructor() {
    // Poll through the store: the effect fetches, the reducer holds the latest positions.
    timer(0, REFRESH_MS)
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.store.dispatch(LivePositionsActions.load()));

    effect(() => {
      const positions = this.visible();
      if (this.host.ready()) this.render(positions);
    });
  }

  protected setAgency(agency: string): void {
    this.setQuery({ mapAgency: agency || null });
  }

  /** Clears every filter: all vehicles on the map again. */
  protected resetFilters(): void {
    this.setQuery({ mapMinDelay: null, mapAgency: null });
  }

  /** Fetches a brigade's schedule for its popup, unless a recent one is at hand; the popup updates when it arrives. */
  private loadSchedule(brigade: string | null): void {
    if (brigade) this.schedules.get(brigade);
  }

  private showSchedule(brigade: string): void {
    for (const p of this.visible()) {
      if (p.brigade === brigade) this.markers.get(p.vehicleNumber)?.setPopupContent(this.popup(p));
    }
  }

  protected setMinDelay(value: string): void {
    const minutes = parseMinDelay(value);
    // replaceUrl: typing a number shouldn't add a history entry per keystroke.
    this.setQuery({ mapMinDelay: minutes }, { replaceUrl: true });
  }

  private popup(p: LivePosition): string {
    return popup(p, p.brigade ? this.schedules.peek(p.brigade) : undefined);
  }

  private render(positions: LivePosition[]): void {
    if (!this.host.map) return;
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
          .addTo(this.host.map);
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
    brigadeSection(p.brigade, schedule, new Date(), true)
  );
}

function delay(seconds: number | null): string {
  if (seconds === null) return 'Delay unknown';
  if (Math.abs(seconds) < 30) return 'On time';
  return `${formatDelay(Math.abs(seconds))} ${seconds > 0 ? 'late' : 'early'}`;
}
