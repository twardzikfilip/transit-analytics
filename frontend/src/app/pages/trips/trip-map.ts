import { Component, ElementRef, effect, input, viewChild } from '@angular/core';
import * as L from 'leaflet';
import { TripRun, TripRunDetail, TripStopTime } from '../../core/models/models';
import { dwellText } from './trip-layout';
import { escapeHtml } from '../../shared/format/html';
import { localTime } from '../../shared/format/time';
import { leafletHost } from '../../shared/map/leaflet-host';
import {
  DELAY_STOPS,
  INK,
  NEUTRAL_GREY,
  NO_DELAY_COLOR,
  WHITE,
  delayColor,
} from '../../shared/palette';
import { Skeleton } from '../../shared/skeleton/skeleton';
import { departureDelayText } from '../brigades/brigade-format';
import { t } from '../../core/i18n/t';

/** Two reports further apart than this aren't joined: the bus wasn't seen in between. */
const GAP_MS = 2 * 60_000;

/** The trip's path as lines between consecutive reports of the same bus, each in the colour of the delay at its start. */
export function delayPath(
  path: TripRunDetail,
): { points: [number, number][]; color: string; tip: string }[] {
  const segments: { points: [number, number][]; color: string; tip: string }[] = [];
  path.points.forEach((p, i) => {
    const next = path.points[i + 1];
    if (!next || next.bus !== p.bus || Date.parse(next.at) - Date.parse(p.at) > GAP_MS) return;
    segments.push({
      points: [
        [p.lat, p.lon],
        [next.lat, next.lon],
      ],
      color: p.delay === null ? NO_DELAY_COLOR : delayColor(p.delay),
      tip: `${localTime(p.at)} · ${p.bus} · ${departureDelayText(p.delay)}`,
    });
  });
  return segments;
}

/** A stop's tooltip: its name and number, when it was due and when the bus passed it. */
export function stopTooltip(
  stop: { index: number; name: string; dwellSeconds?: number | null },
  time: TripStopTime | undefined,
  total: number,
): string {
  const lines = [
    `<strong>${escapeHtml(stop.name)}</strong>`,
    escapeHtml(t('trips.stops.index', { index: stop.index, total })),
  ];
  if (time) {
    lines.push(escapeHtml(t('trips.stops.due', { time: localTime(time.scheduled) })));
    lines.push(
      escapeHtml(
        time.passed
          ? t('trips.stops.passed', {
              time: localTime(time.passed),
              delay: departureDelayText(time.delay),
            })
          : t('trips.stops.notSeen'),
      ),
    );
  }
  const dwell = dwellText(stop.dwellSeconds);
  if (dwell) lines.push(escapeHtml(dwell));
  return lines.join('<br>');
}

/**
 * A trip on a map: its route in grey, and over it the bus's path coloured by how late it was along the way (the
 * colours of the time line), its stops (hover one for its name and times), and where the bus set off and was last
 * seen on the trip. Its stops tell how long the bus stood at each.
 */
@Component({
  selector: 'app-trip-map',
  imports: [Skeleton],
  template: `
    @if (!detail()) {
      <app-skeleton kind="lines" [count]="3" [label]="t('trips.map.loading')" />
    } @else if (!detail()!.points.length) {
      <p class="muted">{{ t('trips.map.none') }}</p>
    }
    <div
      #map
      class="map"
      role="img"
      [attr.aria-label]="t('trips.map.label', { line: run().trip.line })"
    ></div>
    <div class="legend muted">
      <span class="item"><span class="key route"></span>{{ t('trips.map.route') }}</span>
      <span class="item">
        {{ t('trips.ranDelay') }}
        @for (s of delayStops; track s.seconds) {
          <span class="key" [style.border-color]="s.color"></span>{{ t(s.label) }}
        }
      </span>
    </div>
  `,
  styles: `
    :host {
      display: block;
      margin: 0.5rem 0;
    }
    .map {
      height: 360px;
      border-radius: var(--radius);
      border: 1px solid var(--border);
    }
    .legend {
      display: flex;
      flex-wrap: wrap;
      gap: 0.25rem 1rem;
      margin-top: 0.35rem;
      font-size: 0.8rem;
    }
    .item {
      display: inline-flex;
      align-items: center;
      gap: 0.3rem;
    }
    .key {
      display: inline-block;
      width: 1.4rem;
      border-top: 4px solid;
    }
    .key.route {
      border-color: var(--muted);
      opacity: 0.6;
    }
  `,
})
export class TripMap {
  protected readonly t = t;
  readonly run = input.required<TripRun>();
  protected readonly delayStops = DELAY_STOPS;

  /** The trip in detail, from the page (null while it loads, or after a failure, shown as a toast). */
  readonly detail = input<TripRunDetail | null>(null);
  private readonly mapEl = viewChild.required<ElementRef<HTMLDivElement>>('map');
  private readonly host = leafletHost(() => this.mapEl().nativeElement, { grey: true });

  constructor() {
    effect(() => {
      const detail = this.detail();
      if (this.host.ready() && detail) this.draw(detail);
    });
  }

  private draw(path: TripRunDetail): void {
    const layer = this.host.layer;
    layer.clearLayers();
    if (!path.points.length) return;
    let bounds = L.latLngBounds(path.points.map((p) => [p.lat, p.lon] as [number, number]));
    if (path.route.length) {
      const route = L.polyline(path.route, {
        color: NEUTRAL_GREY,
        weight: 7,
        opacity: 0.45,
        interactive: false,
      }).addTo(layer);
      bounds = bounds.extend(route.getBounds());
    }
    for (const s of delayPath(path)) {
      L.polyline(s.points, { color: s.color, weight: 5, opacity: 0.95, lineCap: 'round' })
        .bindTooltip(s.tip, { sticky: true })
        .addTo(layer);
    }
    // The stops, over the path: hover one for its name and times.
    const times = this.run().stops;
    for (const stop of path.stops) {
      L.circleMarker([stop.lat, stop.lon], {
        radius: 4,
        color: INK,
        weight: 1.5,
        fillColor: WHITE,
        fillOpacity: 1,
      })
        .bindTooltip(stopTooltip(stop, times[stop.index - 1], path.stops.length))
        .addTo(layer);
    }
    const first = path.points[0];
    const last = path.points[path.points.length - 1];
    for (const [p, key] of [
      [first, 'trips.map.start'],
      [last, 'trips.map.end'],
    ] as const) {
      L.circleMarker([p.lat, p.lon], {
        radius: 6,
        color: INK,
        weight: 2,
        fillColor: WHITE,
        fillOpacity: 1,
      })
        .bindTooltip(
          t(key, { time: localTime(p.at), bus: p.bus, delay: departureDelayText(p.delay) }),
        )
        .addTo(layer);
    }
    this.host.map.fitBounds(bounds, { padding: [20, 20], maxZoom: 16 });
  }
}
