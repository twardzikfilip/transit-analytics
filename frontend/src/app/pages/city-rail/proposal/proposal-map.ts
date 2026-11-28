import { Component, ElementRef, effect, input, output, viewChild } from '@angular/core';
import * as L from 'leaflet';
import { LatLon, ProposalStationKind, Railway } from '../../../core/models/models';
import { t } from '../../../core/i18n/t';
import { injectCityBoundaries } from '../../../shared/map/city-boundaries';
import { leafletHost } from '../../../shared/map/leaflet-host';
import { INK, NEUTRAL_GREY, RAILWAY_STYLES, WHITE } from '../../../shared/palette';

/** A line drawn on the proposal's map, in its colour, with its number in a badge at its ends. */
export interface MapLine {
  id: string;
  color: string;
  points: LatLon[];
  /** Shown on pointing at it. */
  name: string;
  /** Where its number is shown (none: no badge). */
  badges: LatLon[];
  /** Dashed: a railway no train runs on today. */
  dashed?: boolean;
}

export interface MapStation {
  name: string;
  kind: ProposalStationKind;
  lat: number;
  lon: number;
}

/** A new station's colour on the map, as `.station-new` in the page's legend. */
export const NEW_STATION_COLOR = '#e8590c';

/**
 * The proposal's map: today's railways (grey), the routes on them in their colours, and the stations, each named on
 * pointing at it: those trains call at today (white), those no train calls at today (white, dashed edge), those the
 * city trains pass (small, grey) and the new ones (orange). One line can be highlighted: drawn wider and on top, the
 * others fade.
 */
@Component({
  selector: 'app-proposal-map',
  template: `<div
    #map
    class="leaflet-map"
    role="region"
    [attr.aria-label]="t('cityRail.proposal.mapLabel')"
  ></div>`,
})
export class ProposalMap {
  protected readonly t = t;
  readonly railways = input<Railway[]>([]);
  readonly routes = input.required<MapLine[]>();
  readonly stations = input.required<MapStation[]>();
  readonly highlighted = input<string | null>(null);
  /** A line clicked on the map. */
  readonly choose = output<string>();

  private readonly mapEl = viewChild.required<ElementRef<HTMLDivElement>>('map');
  private readonly drawBoundaries = injectCityBoundaries();
  private readonly host = leafletHost(() => this.mapEl().nativeElement, {
    grey: true,
    zoomSnap: 0.25,
    setUp: (map) => this.drawBoundaries(map),
  });
  private drawn = new Map<string, L.Polyline>();
  private markers: L.Layer[] = [];

  constructor() {
    effect(() => {
      if (this.host.ready()) this.draw(this.railways(), this.routes(), this.stations());
    });
    effect(() => {
      const chosen = this.highlighted();
      this.routes();
      if (this.host.ready()) this.highlight(chosen);
    });
  }

  private draw(railways: Railway[], routes: MapLine[], stations: MapStation[]): void {
    const { map, layer } = this.host;
    layer.clearLayers();
    this.drawn = new Map();
    this.markers = [];
    for (const r of railways) {
      L.polyline(r.points, RAILWAY_STYLES[r.spur ? 'spur' : 'line']).addTo(layer);
    }
    const bounds = L.latLngBounds([]);
    for (const route of routes) {
      const drawn = L.polyline(route.points, {
        color: route.color,
        weight: 5,
        dashArray: route.dashed ? '10 8' : undefined,
      })
        .bindTooltip(text(route.name), { sticky: true })
        .on('click', () => this.choose.emit(route.id))
        .addTo(layer);
      this.drawn.set(route.id, drawn);
      route.points.forEach((p) => bounds.extend(p));
    }
    for (const s of stations) {
      const fresh = s.kind === 'new';
      const passed = s.kind === 'passed';
      const marker = L.circleMarker([s.lat, s.lon], {
        radius: fresh ? 6 : passed ? 3 : 4,
        color: fresh ? WHITE : passed ? NEUTRAL_GREY : INK,
        weight: fresh ? 2 : 1.5,
        dashArray: s.kind === 'unserved' ? '2 2' : undefined,
        fillColor: fresh ? NEW_STATION_COLOR : passed ? NEUTRAL_GREY : WHITE,
        fillOpacity: 1,
      })
        .bindTooltip(text(s.name), { direction: 'top', offset: [0, -6] })
        .addTo(layer);
      this.markers.push(marker);
    }
    for (const route of routes) {
      for (const at of route.badges) {
        const badge = L.marker(at, {
          icon: L.divIcon({
            className: 'line-badge-icon',
            html: `<span class="route-badge" style="--route-color:${route.color}">${escape(route.id)}</span>`,
            iconSize: [36, 22],
            iconAnchor: [18, 11],
          }),
          keyboard: false,
        })
          .on('click', () => this.choose.emit(route.id))
          .addTo(layer);
        this.markers.push(badge);
      }
    }
    if (bounds.isValid()) map.fitBounds(bounds, { padding: [24, 24] });
  }

  private highlight(chosen: string | null): void {
    const known = chosen !== null && this.drawn.has(chosen);
    for (const [id, line] of this.drawn) {
      const on = id === chosen;
      line.setStyle({ weight: on ? 8 : 5, opacity: known && !on ? 0.3 : 1 });
      if (on) line.bringToFront();
    }
    if (known) this.markers.forEach((m) => (m instanceof L.CircleMarker ? m.bringToFront() : null));
  }
}

/** A name as an element, so it is never read as HTML. */
function text(name: string): HTMLElement {
  const el = document.createElement('span');
  el.textContent = name;
  return el;
}

function escape(s: string): string {
  return s.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
}
