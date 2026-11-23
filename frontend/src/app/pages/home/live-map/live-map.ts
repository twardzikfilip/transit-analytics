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
import {
  LINE_TYPES,
  agencies,
  delayedOver,
  ofAgency,
  ofLineType,
  parseLineType,
  parseMinDelay,
} from './delay-filter';
import { escapeHtml } from '../../../shared/format/html';
import { formatDelay, localTime } from '../../../shared/format/time';
import { leafletHost } from '../../../shared/map/leaflet-host';
import {
  BOUNDARY_STYLES,
  BUS_LANE_STYLE,
  DELAY_STOPS,
  LINE_KIND_COLORS,
  NO_DELAY_COLOR,
  delayColor,
} from '../../../shared/palette';
import {
  BrigadeSchedules,
  ScheduleState,
  brigadeSection,
} from '../../../shared/brigade/brigade-schedules';
import { injectBusLanes } from '../../../shared/map/bus-lanes';
import { LineKinds } from '../../../shared/line-kind/line-kinds';
import { cssColor } from '../../../shared/palette';
import { injectCityBoundaries } from '../../../shared/map/city-boundaries';
import { LocalDatePipe } from '../../../shared/i18n/locale-pipes';
import { t, tn } from '../../../core/i18n/t';

const REFRESH_MS = 5000;
/**
 * Where every vehicle is now and how late it is, refreshed every 5 s from the live feed. It doesn't depend on any
 * time window: only on its own filters.
 */
@Component({
  selector: 'app-live-map',
  imports: [LocalDatePipe],
  templateUrl: './live-map.html',
  styleUrl: './live-map.scss',
})
export class LiveMap {
  protected readonly t = t;
  protected readonly tn = tn;
  private readonly store = inject(Store);
  private readonly mapEl = viewChild.required<ElementRef<HTMLDivElement>>('map');

  private readonly host = leafletHost(() => this.mapEl().nativeElement, {
    setUp: (map) => {
      this.drawBoundaries(map);
      this.drawBusLanes(map);
      // A popup's schedule stays as the reader left it, open or folded, as the popup redraws every 5 s.
      map.getContainer().addEventListener('toggle', (event) => this.rememberToggle(event), true);
    },
  });
  /** Brigades whose schedule the reader opened in a popup. */
  private readonly openSchedules = new Set<string>();
  private readonly markers = new Map<
    string,
    { marker: L.Marker; html: string; position: LivePosition }
  >();
  private readonly kinds = inject(LineKinds);

  protected readonly positions = this.store.selectSignal(livePositionsFeature.selectAll);
  protected readonly count = this.store.selectSignal(livePositionsFeature.selectTotal);
  protected readonly lastUpdated = this.store.selectSignal(livePositionsFeature.selectLastUpdated);

  private readonly setQuery = injectSetQuery();
  private readonly params = toSignal(inject(ActivatedRoute).queryParamMap, { requireSync: true });
  /**
   * The map's own filters, kept in the URL under their own names, so the time window and operator of the recorded
   * positions below it never change the map: "delay over N minutes" (?mapMinDelay=5; null = all vehicles), the
   * operator (?mapAgency=BKM; '' = all operators) and the kind of line (?mapLineType=suburban; null = all lines).
   */
  protected readonly minDelay = computed(() => parseMinDelay(this.params().get('mapMinDelay')));
  protected readonly agency = computed(() => this.params().get('mapAgency') ?? '');
  protected readonly lineType = computed(() => parseLineType(this.params().get('mapLineType')));
  /** Operators on the map now, plus the selected one even when none of its vehicles is reporting. */
  protected readonly agencies = computed(() => agencies(this.positions(), this.agency()));
  protected readonly filtering = computed(
    () => this.minDelay() !== null || this.agency() !== '' || this.lineType() !== null,
  );
  /** The filters in force, for the header: "BKM, suburban lines, delayed over 5 min". */
  protected readonly filterSummary = computed(() =>
    [
      this.agency(),
      this.lineType() && t('live.typeLines', { type: t(`live.typesLower.${this.lineType()}`) }),
      this.minDelay() !== null && t('live.delayedOver', { n: this.minDelay() }),
    ]
      .filter(Boolean)
      .join(', '),
  );
  /** The vehicles on the map. */
  protected readonly visible = computed(() =>
    delayedOver(
      ofLineType(ofAgency(this.positions(), this.agency()), this.lineType()),
      this.minDelay(),
    ),
  );

  /** Legend. */
  /** Delays with a ring: over a minute late. */
  protected readonly lateStops = DELAY_STOPS.filter((s) => s.seconds > LATE_RING_SECONDS);
  protected readonly kindLegend = (['urban', 'suburban', 'regional', 'night_urban'] as const).map(
    (kind) => ({ token: LINE_KIND_COLORS[kind].token, label: `live.kinds.${kind}` }),
  );
  protected readonly lineTypes = LINE_TYPES;
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

  protected setLineType(type: string): void {
    this.setQuery({ mapLineType: parseLineType(type) });
  }

  /** Clears every filter: all vehicles on the map again. */
  protected resetFilters(): void {
    this.setQuery({ mapMinDelay: null, mapAgency: null, mapLineType: null });
  }

  /** Fetches a brigade's schedule for its popup, unless a recent one is at hand; the popup updates when it arrives. */
  private loadSchedule(brigade: string | null): void {
    if (brigade) this.schedules.get(brigade);
  }

  private showSchedule(brigade: string): void {
    for (const p of this.visible()) {
      if (p.brigade === brigade)
        this.markers.get(p.vehicleNumber)?.marker.setPopupContent(this.popup(p));
    }
  }

  protected setMinDelay(value: string): void {
    const minutes = parseMinDelay(value);
    // replaceUrl: typing a number shouldn't add a history entry per keystroke.
    this.setQuery({ mapMinDelay: minutes }, { replaceUrl: true });
  }

  private popup(p: LivePosition): string {
    return popup(
      p,
      p.brigade ? this.schedules.peek(p.brigade) : undefined,
      !!p.brigade && this.openSchedules.has(p.brigade),
    );
  }

  private rememberToggle(event: Event): void {
    const details = event.target;
    if (!(details instanceof HTMLDetailsElement) || !details.dataset['brigade']) return;
    const brigade = details.dataset['brigade'];
    // A redraw drawing it as it already was (some browsers fire toggle for that too): nothing changed.
    if (details.open === this.openSchedules.has(brigade)) return;
    if (details.open) this.openSchedules.add(brigade);
    else this.openSchedules.delete(brigade);
    // Redraw the popup as the reader left it (Leaflet lays a popup out from its content, not the page), and pan the
    // map so all of it shows.
    for (const { marker, position } of this.markers.values()) {
      if (marker.isPopupOpen()) marker.setPopupContent(this.popup(position));
    }
  }

  private render(positions: LivePosition[]): void {
    if (!this.host.map) return;
    const seen = new Set<string>();
    for (const p of positions) {
      seen.add(p.vehicleNumber);
      const latLng: L.LatLngExpression = [p.latitude, p.longitude];
      const html = badgeHtml(p, this.kindColor(p));
      const existing = this.markers.get(p.vehicleNumber);
      if (existing) {
        existing.marker.setLatLng(latLng).setPopupContent(this.popup(p));
        existing.position = p;
        if (existing.html !== html) {
          existing.marker.setIcon(badgeIcon(html));
          existing.html = html;
        }
        existing.marker.off('popupopen').on('popupopen', () => this.loadSchedule(p.brigade));
      } else {
        const marker = L.marker(latLng, { icon: badgeIcon(html), keyboard: false })
          .bindPopup(this.popup(p), { maxWidth: 320 })
          .bindTooltip(escapeHtml(p.vehicleNumber), { direction: 'top', offset: [0, -10] })
          .on('popupopen', () => this.loadSchedule(p.brigade))
          .addTo(this.host.map);
        this.markers.set(p.vehicleNumber, { marker, html, position: p });
      }
    }
    for (const [id, { marker }] of this.markers) {
      if (!seen.has(id)) {
        marker.remove();
        this.markers.delete(id);
      }
    }
  }

  /** The colour of the vehicle's kind of line; grey when it reports no line. */
  private kindColor(p: LivePosition): string {
    if (p.subtype) {
      const kind = LINE_KIND_COLORS[p.subtype];
      return cssColor(kind.token, kind.fallback);
    }
    return this.kinds.color(p.routeName) ?? NO_DELAY_COLOR;
  }
}

/** Over this many seconds late, a badge has a ring in its delay's colour. */
export const LATE_RING_SECONDS = 60;

/**
 * A vehicle's badge: its line (its number when it reports none) in its kind's colour, an arrow pointing its way when
 * the feed gives a bearing, and a ring in its delay's colour when over a minute late.
 */
export function badgeHtml(p: LivePosition, kindColor: string): string {
  const late = p.delaySeconds !== null && p.delaySeconds > LATE_RING_SECONDS;
  const arrow =
    p.bearing === null || p.bearing === undefined
      ? ''
      : `<span class="arrow" style="transform: rotate(${Math.round(p.bearing)}deg)"></span>`;
  const style = `--kind: ${kindColor}${late ? `; --delay: ${delayColor(p.delaySeconds)}` : ''}`;
  return `<span class="pill${late ? ' late' : ''}" style="${style}">${arrow}${escapeHtml(p.routeName ?? p.vehicleNumber)}</span>`;
}

function badgeIcon(html: string): L.DivIcon {
  return L.divIcon({ html, className: 'live-bus', iconSize: [0, 0] });
}

function popup(p: LivePosition, schedule: ScheduleState | undefined, open: boolean): string {
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
    ? `<br>${escapeHtml(t('live.next', { stop: next.name }))}${next.scheduledArrival ? ` (${localTime(next.scheduledArrival)})` : ''}`
    : '';
  const seen = p.timestamp
    ? `<br><span class="muted">${escapeHtml(t('live.asOf', { time: localTime(p.timestamp) }))}</span>`
    : '';
  return (
    `<strong>${line || escapeHtml(p.vehicleNumber)}</strong><br>${vehicle}<br>${delay(p.delaySeconds)}${nextStop}${seen}` +
    brigadeSection(p.brigade, schedule, new Date(), true, open)
  );
}

function delay(seconds: number | null): string {
  if (seconds === null) return t('live.delayUnknown');
  if (Math.abs(seconds) < 30) return t('live.onTime');
  return t(seconds > 0 ? 'live.late' : 'live.early', { delay: formatDelay(Math.abs(seconds)) });
}
