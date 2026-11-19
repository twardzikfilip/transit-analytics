import {
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  inject,
  input,
  viewChild,
} from '@angular/core';
import * as L from 'leaflet';
import { createBaseMap } from './base-map';

/** A line on a {@link PathMap}: points [lat, lon] in order, its colour (a CSS colour) and its name in the legend. */
export interface PathLine {
  points: [number, number][];
  color: string;
  label: string;
  /** Default 3. */
  weight?: number;
  opacity?: number;
  /** A dot on every point (e.g. each recorded report). */
  dots?: boolean;
  /** A ring on the last point, e.g. where a bus was last seen; its tooltip. */
  end?: string;
}

/**
 * Lines on a small map (routes, buses' paths), zoomed to fit them all, with a legend naming each. Scrolling the page
 * doesn't zoom it.
 */
@Component({
  selector: 'app-path-map',
  template: `
    <div #map class="map" role="img" [attr.aria-label]="label()"></div>
    <ul class="legend">
      @for (l of lines(); track l.label) {
        <li>
          <span
            class="key"
            [style.border-color]="l.color"
            [style.border-top-width.px]="l.weight ?? 3"
          ></span>
          {{ l.label }}
        </li>
      }
    </ul>
  `,
  styles: `
    :host {
      display: grid;
      gap: 0.35rem;
      max-width: 48rem;
    }

    .map {
      height: 280px;
      border-radius: 6px;
      border: 1px solid var(--border);
    }

    .legend {
      display: flex;
      flex-wrap: wrap;
      gap: 0.25rem 1rem;
      margin: 0;
      padding: 0;
      list-style: none;
      font-size: 0.8rem;
      color: var(--muted);

      li {
        display: flex;
        align-items: center;
        gap: 0.4rem;
      }
    }

    .key {
      display: inline-block;
      width: 1.4rem;
      height: 0;
      border-top: 3px solid;
    }
  `,
})
export class PathMap {
  readonly lines = input.required<PathLine[]>();
  readonly label = input('Map');

  private readonly mapEl = viewChild.required<ElementRef<HTMLDivElement>>('map');

  constructor() {
    let map: L.Map | undefined;
    inject(DestroyRef).onDestroy(() => map?.remove());
    afterNextRender(() => {
      map = createBaseMap(this.mapEl().nativeElement);
      map.scrollWheelZoom.disable();
      let bounds: L.LatLngBounds | null = null;
      for (const line of this.lines()) {
        if (!line.points.length) continue;
        const drawn = L.polyline(line.points, {
          color: line.color,
          weight: line.weight ?? 3,
          opacity: line.opacity ?? 1,
          interactive: false,
        }).addTo(map);
        bounds = bounds ? bounds.extend(drawn.getBounds()) : drawn.getBounds();
        if (line.dots) {
          for (const p of line.points) {
            L.circleMarker(p, {
              radius: 3,
              color: line.color,
              fillColor: line.color,
              fillOpacity: 1,
              weight: 1,
              interactive: false,
            }).addTo(map);
          }
        }
        if (line.end) {
          L.circleMarker(line.points[line.points.length - 1], {
            radius: 7,
            color: line.color,
            weight: 3,
            fillColor: '#fff',
            fillOpacity: 1,
          })
            .bindTooltip(line.end)
            .addTo(map);
        }
      }
      if (bounds) map.fitBounds(bounds, { padding: [20, 20], maxZoom: 17 });
    });
  }
}
