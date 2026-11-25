import { Component, ElementRef, effect, input, output, viewChild } from '@angular/core';
import * as L from 'leaflet';
import { RailRoute, Railway } from '../../core/models/models';
import { t } from '../../core/i18n/t';
import { injectCityBoundaries } from '../../shared/map/city-boundaries';
import { leafletHost } from '../../shared/map/leaflet-host';
import { INK, RAILWAY_STYLES, WHITE } from '../../shared/palette';

export interface ColoredRoute extends RailRoute {
  color: string;
  name: string;
}

/**
 * The railways (grey), the routes on them in their colours, and their stations. One route can be highlighted: it is
 * drawn wider and on top, the others fade.
 */
@Component({
  selector: 'app-rail-map',
  template: `<div
    #map
    class="leaflet-map"
    role="region"
    [attr.aria-label]="t('cityRail.mapLabel')"
  ></div>`,
})
export class RailMap {
  protected readonly t = t;
  readonly railways = input.required<Railway[]>();
  readonly routes = input.required<ColoredRoute[]>();
  readonly highlighted = input<string | null>(null);
  /** A route clicked on the map. */
  readonly choose = output<string>();

  private readonly mapEl = viewChild.required<ElementRef<HTMLDivElement>>('map');
  private readonly drawBoundaries = injectCityBoundaries();
  private readonly host = leafletHost(() => this.mapEl().nativeElement, {
    grey: true,
    zoomSnap: 0.25,
    setUp: (map) => this.drawBoundaries(map),
  });
  private lines = new Map<string, L.Polyline>();
  private stations: L.CircleMarker[] = [];

  constructor() {
    effect(() => {
      if (this.host.ready()) this.draw(this.railways(), this.routes());
    });
    effect(() => {
      const chosen = this.highlighted();
      this.routes();
      if (this.host.ready()) this.highlight(chosen);
    });
  }

  private draw(railways: Railway[], routes: ColoredRoute[]): void {
    const { map, layer } = this.host;
    layer.clearLayers();
    this.lines = new Map();
    const bounds = L.latLngBounds([]);
    for (const r of railways) {
      const line = L.polyline(r.points, RAILWAY_STYLES[r.spur ? 'spur' : 'line']).addTo(layer);
      if (r.ref) line.bindTooltip(t('cityRail.railwayLine', { ref: r.ref }), { sticky: true });
      r.points.forEach((p) => bounds.extend(p));
    }
    for (const route of routes) {
      const line = L.polyline(route.points, { color: route.color, weight: 5 })
        .bindTooltip(text(route.name), { sticky: true })
        .on('click', () => this.choose.emit(route.id))
        .addTo(layer);
      this.lines.set(route.id, line);
    }
    const stations = new Map(routes.flatMap((r) => r.stations).map((s) => [s.name, s]));
    this.stations = [...stations.values()].map((s) =>
      L.circleMarker([s.lat, s.lon], {
        radius: 4,
        color: INK,
        weight: 1.5,
        fillColor: WHITE,
        fillOpacity: 1,
      })
        .bindTooltip(text(s.name))
        .addTo(layer),
    );
    if (bounds.isValid()) map.fitBounds(bounds, { padding: [12, 12] });
  }

  private highlight(chosen: string | null): void {
    const known = chosen !== null && this.lines.has(chosen);
    for (const [id, line] of this.lines) {
      const on = id === chosen;
      line.setStyle({ weight: on ? 8 : 5, opacity: known && !on ? 0.3 : 1 });
      // Routes share track near the city's station; the stations stay above them all.
      if (on) line.bringToFront();
    }
    if (known) this.stations.forEach((s) => s.bringToFront());
  }
}

/** A name as an element, so it is never read as HTML. */
function text(name: string): HTMLElement {
  const el = document.createElement('span');
  el.textContent = name;
  return el;
}
