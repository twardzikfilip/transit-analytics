import {
  Component,
  ElementRef,
  computed,
  effect,
  input,
  output,
  untracked,
  viewChild,
} from '@angular/core';
import * as L from 'leaflet';
import { BusLaneStretch, LaneStretchFigures } from '../../core/models/models';
import { escapeHtml } from '../../shared/format/html';
import { leafletHost } from '../../shared/map/leaflet-host';
import { injectCityBoundaries } from '../../shared/map/city-boundaries';
import {
  NO_SPEED_COLOR,
  SPEED_SCALE,
  delayText,
  headingText,
  speedColor,
  speedText,
} from './lane-format';
import { t } from '../../core/i18n/t';

/**
 * Every bus lane on a grey map, coloured by the average speed of buses on it in the window (darker: slower); lanes
 * without recordings grey. Hover a lane for its figures; click it to choose it (the table follows).
 */
@Component({
  selector: 'app-bus-lane-map',
  template: `
    <div #map class="leaflet-map" role="region" [attr.aria-label]="t('busLanes.speedMap')"></div>
    <div class="legend inset">
      <span class="muted">{{ t('busLanes.speedLegend') }}</span>
      @for (s of scale; track s.label) {
        <span class="item"
          ><span class="swatch" [style.background]="s.color"></span
          >{{ s.label.startsWith('busLanes.') ? t(s.label) : s.label }}</span
        >
      }
      <span class="item"
        ><span class="swatch" [style.background]="noSpeed"></span
        >{{ t('busLanes.notRecorded') }}</span
      >
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
  `,
})
export class BusLaneMap {
  protected readonly t = t;
  readonly stretches = input.required<BusLaneStretch[]>();
  /** Figures per stretch id; stretches missing from it weren't recorded. */
  readonly figures = input<Map<number, LaneStretchFigures>>(new Map());
  readonly selected = input<number | null>(null);
  readonly choose = output<number>();

  protected readonly scale = SPEED_SCALE;
  protected readonly noSpeed = NO_SPEED_COLOR;

  private readonly mapEl = viewChild.required<ElementRef<HTMLDivElement>>('map');
  private readonly drawBoundaries = injectCityBoundaries();
  private readonly host = leafletHost(() => this.mapEl().nativeElement, {
    canvas: true,
    grey: true,
    setUp: (map) => this.drawBoundaries(map),
  });
  private readonly lines = new Map<number, L.Polyline[]>();
  private framed = false;
  private chosenOnMap: number | null = null;

  private readonly drawn = computed(() => ({
    stretches: this.stretches(),
    figures: this.figures(),
  }));

  constructor() {
    effect(() => {
      const { stretches, figures } = this.drawn();
      if (this.host.ready()) {
        this.draw(stretches, figures);
        untracked(() => this.highlight(this.selected(), false));
      }
    });
    effect(() => {
      const id = this.selected();
      if (this.host.ready()) untracked(() => this.highlight(id, true));
    });
  }

  private draw(stretches: BusLaneStretch[], figures: Map<number, LaneStretchFigures>): void {
    const layer = this.host.layer;
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
        `${escapeHtml(t('busLanes.tip.km', { km: s.km.toFixed(2) }))}<br>` +
        escapeHtml(
          f
            ? t('busLanes.tip.average', {
                speed: speedText(f.figures.speed),
                peak: speedText(f.figures.peakSpeed),
                off: speedText(f.figures.offPeakSpeed),
              })
            : t('busLanes.tip.noBuses'),
        ) +
        (f
          ? `<br>${escapeHtml(t('busLanes.tip.delay', { delay: delayText(f.figures.delayPerKm) }))}` +
            (f.opposite
              ? `<br>${escapeHtml(t('busLanes.tip.otherWay', { speed: speedText(f.opposite.speed) }))}`
              : '')
          : '');
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
      this.host.map.fitBounds(bounds, { padding: [24, 24] });
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
      this.host.map.fitBounds(bounds, { padding: [60, 60], maxZoom: 17 });
    }
    this.chosenOnMap = null;
  }
}
