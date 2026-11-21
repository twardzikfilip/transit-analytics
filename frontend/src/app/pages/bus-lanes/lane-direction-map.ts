import { Component, ElementRef, effect, input, signal, viewChild } from '@angular/core';
import * as L from 'leaflet';
import { BusLane } from '../../core/models/models';
import { escapeHtml } from '../../shared/format/html';
import { leafletHost } from '../../shared/map/leaflet-host';
import { injectCityBoundaries } from '../../shared/map/city-boundaries';

/** One direction only, and both (one section with a lane each way, or both carriageways with one). */
import { BOTH_WAYS_COLOR, ONE_WAY_COLOR } from '../../shared/palette';

export { BOTH_WAYS_COLOR, ONE_WAY_COLOR };
/** Arrows from this zoom in; further out they would cover the lanes. */
export const ARROW_ZOOM = 15;
/** An arrow about every this many metres along a lane, at least one per section. */
const ARROW_EVERY = 120;

/** Degrees clockwise from north on the map, from a to b ([lat, lon]). */
export function mapHeading(a: [number, number], b: [number, number]): number {
  const dx = (b[1] - a[1]) * Math.cos((a[0] * Math.PI) / 180);
  const dy = b[0] - a[0];
  return ((Math.atan2(dx, dy) * 180) / Math.PI + 360) % 360;
}

function meters(a: [number, number], b: [number, number]): number {
  const dx = (b[1] - a[1]) * 111_320 * Math.cos((a[0] * Math.PI) / 180);
  return Math.hypot(dx, (b[0] - a[0]) * 111_320);
}

/**
 * Points along a polyline about every `every` metres (the middle of a shorter one), with the heading there:
 * where to put the direction arrows.
 */
export function arrowPoints(
  points: [number, number][],
  every = ARROW_EVERY,
): { at: [number, number]; heading: number }[] {
  const lengths = points.slice(1).map((p, i) => meters(points[i], p));
  const total = lengths.reduce((a, b) => a + b, 0);
  if (total === 0) return [];
  const count = Math.max(1, Math.floor(total / every));
  const out: { at: [number, number]; heading: number }[] = [];
  for (let k = 0; k < count; k++) {
    let d = ((k + 0.5) * total) / count;
    for (let i = 0; i < lengths.length; i++) {
      if (d <= lengths[i] || i === lengths.length - 1) {
        const t = lengths[i] === 0 ? 0 : Math.min(1, d / lengths[i]);
        const [a, b] = [points[i], points[i + 1]];
        out.push({
          at: [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t],
          heading: mapHeading(a, b),
        });
        break;
      }
      d -= lengths[i];
    }
  }
  return out;
}

/**
 * Where the bus lanes are, without figures: each section coloured by whether buses have a lane one way or both
 * ways, and (zoomed in) arrows pointing the way each lane runs.
 */
@Component({
  selector: 'app-lane-direction-map',
  template: `
    <div
      #map
      class="leaflet-map"
      role="region"
      aria-label="Map of bus lanes and their directions"
    ></div>
    <div class="legend inset">
      <span class="item"
        ><span class="swatch" [style.background]="oneWay"></span>Bus lane one way</span
      >
      <span class="item"
        ><span class="swatch" [style.background]="bothWays"></span>Bus lanes both ways (on one
        section, or on both carriageways)</span
      >
      <span class="item"><span class="arrow" aria-hidden="true">➤</span>the way the lane runs</span>
      @if (!arrowsShown()) {
        <span class="muted">Zoom in to see the arrows.</span>
      }
    </div>
  `,
  styles: `
    :host {
      display: block;
      --map-height: 560px;
      --map-height-mobile: 380px;
    }

    @media (max-width: 600px) {
    }

    .swatch {
      display: inline-block;
      width: 1.4rem;
      height: 6px;
      border-radius: 2px;
    }

    .map ::ng-deep .lane-arrow {
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 13px;
      line-height: 1;
      color: #fff;
      text-shadow:
        0 0 2px #000,
        0 0 1px #000;
    }
  `,
})
export class LaneDirectionMap {
  readonly lanes = input.required<BusLane[]>();

  protected readonly oneWay = ONE_WAY_COLOR;
  protected readonly bothWays = BOTH_WAYS_COLOR;
  protected readonly arrowsShown = signal(false);

  private readonly mapEl = viewChild.required<ElementRef<HTMLDivElement>>('map');
  private readonly drawBoundaries = injectCityBoundaries();
  private readonly host = leafletHost(() => this.mapEl().nativeElement, {
    canvas: true,
    grey: true,
    setUp: (map) => {
      this.drawBoundaries(map);
      map.on('zoomend', () => this.showArrows());
    },
  });
  private readonly arrows = L.layerGroup();

  constructor() {
    effect(() => {
      const lanes = this.lanes();
      if (this.host.ready()) this.draw(lanes);
    });
  }

  private draw(lanes: BusLane[]): void {
    this.host.layer.clearLayers();
    this.arrows.clearLayers();
    const bounds = L.latLngBounds([]);
    for (const lane of lanes) {
      const both = lane.bothWays || (lane.forward > 0 && lane.backward > 0);
      const ways =
        lane.forward > 0 && lane.backward > 0
          ? 'a bus lane each way on this section'
          : both
            ? 'a bus lane this way; the other carriageway has one the other way'
            : 'a bus lane one way only';
      L.polyline(lane.points, {
        color: both ? BOTH_WAYS_COLOR : ONE_WAY_COLOR,
        weight: 6,
        opacity: 0.85,
        lineCap: 'butt',
      })
        .bindTooltip(`<strong>${escapeHtml(lane.name ?? 'Unnamed road')}</strong><br>${ways}`, {
          sticky: true,
        })
        .addTo(this.host.layer);
      const directions: [number, number][][] = [];
      if (lane.forward > 0) directions.push(lane.points);
      if (lane.backward > 0) directions.push([...lane.points].reverse());
      for (const path of directions) {
        for (const a of arrowPoints(path)) {
          L.marker(a.at, {
            interactive: false,
            keyboard: false,
            icon: L.divIcon({
              className: 'lane-arrow',
              iconSize: [14, 14],
              // ➤ points east (90°); turned to the lane's heading.
              html: `<span style="transform: rotate(${Math.round(a.heading - 90)}deg)">➤</span>`,
            }),
          }).addTo(this.arrows);
        }
      }
      for (const p of lane.points) bounds.extend(p);
    }
    if (bounds.isValid()) this.host.map.fitBounds(bounds, { padding: [24, 24] });
    this.showArrows();
  }

  /** Arrows only when zoomed in enough to tell them apart. */
  private showArrows(): void {
    const show = this.host.map.getZoom() >= ARROW_ZOOM;
    if (show && !this.host.map.hasLayer(this.arrows)) this.arrows.addTo(this.host.map);
    if (!show && this.host.map.hasLayer(this.arrows)) this.arrows.remove();
    this.arrowsShown.set(show);
  }
}
