import {
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  effect,
  inject,
  input,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import * as L from 'leaflet';
import { DelayStretch } from '../../core/models/models';
import { escapeHtml } from '../../shared/format/html';
import { createBaseMap } from '../../shared/map/base-map';
import { DELAY_CHANGE_CLASSES, changeClass, signedChange } from './delay-scale';

/** Stretches measured fewer times in the chosen part of the day aren't drawn. */
export const MIN_MAP_RUNS = 5;
/** Each direction is drawn this many pixels to the right of the road's centre, so both directions show. */
const OFFSET_PX = 2.5;

/** A point moved `px` pixels (at this zoom) to the right of the direction from `a` to `b`. */
export function offsetLine(
  a: [number, number],
  b: [number, number],
  px: number,
  zoom: number,
): [number, number][] {
  const metersPerPx = (40_075_016.686 * Math.cos((a[0] * Math.PI) / 180)) / 2 ** (zoom + 8);
  const meters = px * metersPerPx;
  const mPerLat = 111_320;
  const mPerLon = 111_320 * Math.cos((a[0] * Math.PI) / 180);
  const dx = (b[1] - a[1]) * mPerLon;
  const dy = (b[0] - a[0]) * mPerLat;
  const length = Math.hypot(dx, dy) || 1;
  // Right of the direction of travel: (dy, −dx) normalised.
  const ox = (dy / length) * meters;
  const oy = (-dx / length) * meters;
  const shift = (p: [number, number]): [number, number] => [
    p[0] + oy / mPerLat,
    p[1] + ox / mPerLon,
  ];
  return [shift(a), shift(b)];
}

/**
 * Every stretch between two stops on a grey map, coloured by the delay buses gain on it (red) or recover (blue)
 * in the chosen part of the day. The two directions are drawn side by side.
 */
@Component({
  selector: 'app-stretch-map',
  template: `
    <div #map class="map" role="region" aria-label="Map of delay generated between stops"></div>
    <div class="legend">
      <span class="muted">Change in delay per bus:</span>
      @for (c of classes; track c.to) {
        <span class="item"
          ><span class="swatch" [style.background]="c.color"></span>{{ c.label }}</span
        >
      }
    </div>
  `,
  styles: `
    :host {
      display: block;
    }

    .map {
      height: 560px;
    }

    @media (max-width: 600px) {
      .map {
        height: 380px;
      }
    }

    .map ::ng-deep .grey-tiles {
      filter: grayscale(1) contrast(0.85) brightness(1.05);
    }

    .legend {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.4rem 1rem;
      padding: 0.6rem 1rem;
      font-size: 0.85rem;
    }

    .item {
      display: inline-flex;
      align-items: center;
      gap: 0.35rem;
    }

    .swatch {
      display: inline-block;
      width: 22px;
      height: 6px;
      border-radius: 2px;
    }
  `,
})
export class StretchMap {
  readonly stretches = input.required<DelayStretch[]>();
  /** Index of the part of the day (in each stretch's per-band figures). */
  readonly band = input(0);
  /** Recorded days, for the per-day figures in tooltips. */
  readonly days = input(1);

  private readonly mapEl = viewChild.required<ElementRef<HTMLDivElement>>('map');
  private map?: L.Map;
  private layer?: L.LayerGroup;
  private readonly ready = signal(false);
  private fitted = false;

  protected readonly classes = DELAY_CHANGE_CLASSES;

  constructor() {
    afterNextRender(() => {
      this.map = createBaseMap(this.mapEl().nativeElement, { canvas: true, grey: true });
      this.layer = L.layerGroup().addTo(this.map);
      // The offset is in pixels: redraw at each zoom.
      this.map.on('zoomend', () => untracked(() => this.draw()));
      this.ready.set(true);
    });
    inject(DestroyRef).onDestroy(() => this.map?.remove());
    effect(() => {
      this.stretches();
      this.band();
      if (this.ready()) untracked(() => this.draw());
    });
  }

  private draw(): void {
    const layer = this.layer!;
    layer.clearLayers();
    const band = this.band();
    const zoom = this.map!.getZoom();
    const bounds = L.latLngBounds([]);
    const busiest = Math.max(0, ...this.stretches().map((s) => s.runs[0]));
    // Small changes first, so the strongest red and blue lie on top.
    const shown = this.stretches()
      .filter((s) => s.runs[band] >= MIN_MAP_RUNS && s.meanSeconds[band] !== null)
      .sort((a, b) => Math.abs(a.meanSeconds[band]!) - Math.abs(b.meanSeconds[band]!));
    for (const s of shown) {
      const mean = s.meanSeconds[band]!;
      const points = offsetLine([s.fromLat, s.fromLon], [s.toLat, s.toLon], OFFSET_PX, zoom);
      L.polyline(points, {
        color: changeClass(mean).color,
        weight: zoom >= 15 ? 5 : 3.5,
        opacity: 0.95,
        lineCap: 'butt',
      })
        .bindTooltip(
          `<strong>${escapeHtml(s.from)} → ${escapeHtml(s.to)}</strong><br>` +
            `${signedChange(mean)} min per bus, ${s.runs[band]} buses ` +
            `(${Math.round(s.runs[band] / Math.max(1, this.days()))} a day)<br>` +
            `Lines ${escapeHtml(s.lines.join(', '))}`,
          { sticky: true },
        )
        .addTo(layer);
      // Frame the busier part of the network (the city); suburban ends are a zoom-out away.
      if (!this.fitted && s.runs[0] >= busiest * 0.1) points.forEach((p) => bounds.extend(p));
    }
    // Frame the network once; later redraws (zoom, part of the day) keep the view.
    if (!this.fitted && bounds.isValid()) {
      this.fitted = true;
      this.map!.fitBounds(bounds, { padding: [12, 12] });
    }
  }
}
