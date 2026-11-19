import {
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import * as L from 'leaflet';
import { BusLaneStretch, LaneStretchFigures } from '../../core/models/models';
import { escapeHtml } from '../../shared/format/html';
import { createBaseMap } from '../../shared/map/base-map';
import { injectCityBoundaries } from '../../shared/map/city-boundaries';
import {
  NO_SPEED_COLOR,
  SPEED_SCALE,
  delayText,
  headingText,
  speedColor,
  speedText,
} from './lane-format';

/**
 * Every bus lane on a grey map, coloured by the average speed of buses on it in the window (darker: slower); lanes
 * without recordings grey. Hover a lane for its figures; click it to choose it (the table follows).
 */
@Component({
  selector: 'app-bus-lane-map',
  template: `
    <div #map class="map" role="region" aria-label="Map of bus lanes by speed"></div>
    <div class="legend">
      <span class="muted">Average speed on the lane, km/h:</span>
      @for (s of scale; track s.label) {
        <span class="item"
          ><span class="swatch" [style.background]="s.color"></span>{{ s.label }}</span
        >
      }
      <span class="item"
        ><span class="swatch" [style.background]="noSpeed"></span>not recorded</span
      >
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
      width: 1.4rem;
      height: 6px;
      border-radius: 2px;
    }
  `,
})
export class BusLaneMap {
  readonly stretches = input.required<BusLaneStretch[]>();
  /** Figures per stretch id; stretches missing from it weren't recorded. */
  readonly figures = input<Map<number, LaneStretchFigures>>(new Map());
  readonly selected = input<number | null>(null);
  readonly choose = output<number>();

  protected readonly scale = SPEED_SCALE;
  protected readonly noSpeed = NO_SPEED_COLOR;

  private readonly mapEl = viewChild.required<ElementRef<HTMLDivElement>>('map');
  private readonly drawBoundaries = injectCityBoundaries();
  private map?: L.Map;
  private layer?: L.LayerGroup;
  private readonly lines = new Map<number, L.Polyline[]>();
  private readonly ready = signal(false);
  private framed = false;
  private chosenOnMap: number | null = null;

  private readonly drawn = computed(() => ({
    stretches: this.stretches(),
    figures: this.figures(),
  }));

  constructor() {
    afterNextRender(() => {
      this.map = createBaseMap(this.mapEl().nativeElement, { canvas: true, grey: true });
      this.drawBoundaries(this.map);
      this.layer = L.layerGroup().addTo(this.map);
      this.ready.set(true);
    });
    inject(DestroyRef).onDestroy(() => this.map?.remove());
    effect(() => {
      const { stretches, figures } = this.drawn();
      if (this.ready()) {
        this.draw(stretches, figures);
        untracked(() => this.highlight(this.selected(), false));
      }
    });
    effect(() => {
      const id = this.selected();
      if (this.ready()) untracked(() => this.highlight(id, true));
    });
  }

  private draw(stretches: BusLaneStretch[], figures: Map<number, LaneStretchFigures>): void {
    const layer = this.layer!;
    layer.clearLayers();
    this.lines.clear();
    const bounds = L.latLngBounds([]);
    // Unrecorded first, so recorded lanes lie on top.
    const ordered = [...stretches].sort(
      (a, b) => Number(figures.has(a.id)) - Number(figures.has(b.id)),
    );
    for (const s of ordered) {
      const f = figures.get(s.id);
      const color = speedColor(f?.figures.speed);
      const tip =
        `<strong>${escapeHtml(s.name)}</strong> ${headingText(s.heading)}<br>` +
        `${s.km.toFixed(2)} km of bus lane<br>` +
        (f
          ? `${speedText(f.figures.speed)} on average (peak ${speedText(f.figures.peakSpeed)}, ` +
            `off-peak ${speedText(f.figures.offPeakSpeed)})<br>delay ${delayText(f.figures.delayPerKm)}` +
            (f.opposite ? `<br>other way, no lane: ${speedText(f.opposite.speed)}` : '')
          : 'no buses recorded on it in this window');
      const polylines = s.paths.map((path) =>
        L.polyline(path, { color, weight: 6, opacity: 0.9, lineCap: 'butt' })
          .bindTooltip(tip, { sticky: true })
          .on('click', () => {
            this.chosenOnMap = s.id;
            this.choose.emit(s.id);
          })
          .addTo(layer),
      );
      this.lines.set(s.id, polylines);
      for (const path of s.paths) for (const p of path) bounds.extend(p);
    }
    if (!this.framed && bounds.isValid()) {
      this.map!.fitBounds(bounds, { padding: [24, 24] });
      this.framed = true;
    }
  }

  /** Thickens and outlines the chosen stretch; moves the map to it when it was chosen in the table. */
  private highlight(id: number | null, move: boolean): void {
    for (const [key, polylines] of this.lines) {
      for (const line of polylines) line.setStyle({ weight: key === id ? 10 : 6 });
      if (key === id) polylines.forEach((l) => l.bringToFront());
    }
    const chosen = id === null ? undefined : this.lines.get(id);
    if (chosen && move && id !== this.chosenOnMap) {
      const bounds = L.latLngBounds([]);
      chosen.forEach((l) => bounds.extend(l.getBounds()));
      this.map!.fitBounds(bounds, { padding: [60, 60], maxZoom: 17 });
    }
    this.chosenOnMap = null;
  }
}
