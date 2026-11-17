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
import { Hotspot } from '../../core/models/models';
import { escapeHtml } from '../../shared/format/html';
import { createBaseMap } from '../../shared/map/base-map';
import { cssColor } from '../../shared/palette';
import { lostTime, nearText, waitText } from './hotspot-format';

/** Marker radius in px of the hotspot with the most time lost; the others are smaller, by area. */
export const MAX_RADIUS = 22;
const MIN_RADIUS = 3;

/** Radius in px for a hotspot, so the circle's area is in proportion to the time lost there. */
export function radiusFor(hours: number, maxHours: number): number {
  if (maxHours <= 0) return MIN_RADIUS;
  return MIN_RADIUS + (MAX_RADIUS - MIN_RADIUS) * Math.sqrt(hours / maxHours);
}

/**
 * The hotspots on a grey map: a circle each, sized by the time buses lost there. Hover one for its numbers; click it
 * to choose it (the table follows). The chosen one is outlined, with its spread in metres around it.
 */
@Component({
  selector: 'app-hotspot-map',
  template: `
    <div #map class="map" role="region" aria-label="Map of stop hotspots by time lost"></div>
    <div class="legend">
      <span class="muted">Time lost:</span>
      @for (s of samples(); track s.hours) {
        <span class="item">
          <svg
            [attr.width]="maxRadius * 2 + 2"
            [attr.height]="maxRadius * 2 + 2"
            aria-hidden="true"
          >
            <circle
              [attr.cx]="maxRadius + 1"
              [attr.cy]="maxRadius + 1"
              [attr.r]="s.radius"
              [attr.fill]="color"
              fill-opacity="0.55"
              [attr.stroke]="color"
            />
          </svg>
          {{ s.label }}
        </span>
      }
    </div>
  `,
  styles: `
    :host {
      display: block;
    }

    .map {
      height: 520px;
    }

    @media (max-width: 600px) {
      .map {
        height: 360px;
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
  `,
})
export class HotspotMap {
  readonly hotspots = input.required<Hotspot[]>();
  /** The chosen hotspot's id, or null. */
  readonly selected = input<number | null>(null);
  readonly choose = output<number>();

  private readonly mapEl = viewChild.required<ElementRef<HTMLDivElement>>('map');
  private map?: L.Map;
  private layer?: L.LayerGroup;
  private spread?: L.Circle;
  private readonly markers = new Map<number, L.CircleMarker>();
  private readonly ready = signal(false);
  /** A hotspot clicked on the map: the map stays where it is when it becomes the chosen one. */
  private chosenOnMap: number | null = null;

  protected readonly color = cssColor('--chart-series-2', '#eb6834');
  protected readonly maxRadius = MAX_RADIUS;
  private readonly maxHours = computed(() => Math.max(0, ...this.hotspots().map((h) => h.hours)));
  /** Circles in the legend: all, a quarter and a twentieth of the most time lost. */
  protected readonly samples = computed(() => {
    const max = this.maxHours();
    if (max <= 0) return [];
    return [1, 0.25, 0.05].map((f) => ({
      hours: max * f,
      radius: radiusFor(max * f, max),
      label: lostTime(max * f),
    }));
  });

  constructor() {
    afterNextRender(() => {
      this.map = createBaseMap(this.mapEl().nativeElement, { canvas: true, grey: true });
      this.layer = L.layerGroup().addTo(this.map);
      this.ready.set(true);
    });
    inject(DestroyRef).onDestroy(() => this.map?.remove());
    effect(() => {
      const hotspots = this.hotspots();
      if (this.ready()) {
        this.draw(hotspots);
        untracked(() => this.highlight(this.selected(), false));
      }
    });
    effect(() => {
      const id = this.selected();
      if (this.ready()) untracked(() => this.highlight(id, true));
    });
  }

  private draw(hotspots: Hotspot[]): void {
    const layer = this.layer!;
    layer.clearLayers();
    this.markers.clear();
    this.spread = undefined;
    const max = this.maxHours();
    const bounds = L.latLngBounds([]);
    // The most time lost first, so big circles lie underneath and the small ones on top stay within reach.
    for (const h of hotspots) {
      const marker = L.circleMarker([h.lat, h.lon], {
        radius: radiusFor(h.hours, max),
        color: this.color,
        weight: 1,
        fillColor: this.color,
        fillOpacity: 0.55,
      })
        .bindTooltip(
          `<strong>#${h.id} ${escapeHtml(nearText(h))}</strong><br>` +
            `${lostTime(h.hours)} lost, ${h.minutesPerDay.toFixed(1)} min a day<br>` +
            `${h.standstills} standstills, typically ${waitText(h.medianSeconds)}`,
        )
        .on('click', () => {
          this.chosenOnMap = h.id;
          this.choose.emit(h.id);
        })
        .addTo(layer);
      this.markers.set(h.id, marker);
    }
    // Frame the places where most time is lost.
    for (const h of hotspots.slice(0, 40)) bounds.extend([h.lat, h.lon]);
    if (bounds.isValid()) this.map!.fitBounds(bounds, { padding: [24, 24], maxZoom: 15 });
  }

  /** Outlines the chosen hotspot and shows its spread; moves the map to it when it was chosen in the table. */
  private highlight(id: number | null, move: boolean): void {
    for (const [key, marker] of this.markers) {
      marker.setStyle({ weight: key === id ? 3 : 1, color: key === id ? '#1f2933' : this.color });
    }
    this.spread?.remove();
    this.spread = undefined;
    const h = this.hotspots().find((x) => x.id === id);
    if (!h) return;
    this.markers.get(h.id)?.bringToFront();
    this.spread = L.circle([h.lat, h.lon], {
      radius: Math.max(5, h.radiusMeters),
      color: '#1f2933',
      weight: 1,
      dashArray: '4 4',
      fill: false,
      interactive: false,
    }).addTo(this.layer!);
    if (move && h.id !== this.chosenOnMap) {
      this.map!.setView([h.lat, h.lon], Math.max(this.map!.getZoom(), 16));
    }
    this.chosenOnMap = null;
  }
}
