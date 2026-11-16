import {
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import * as L from 'leaflet';
import { SegmentLoad } from '../../core/models/models';
import { SEQUENTIAL_BLUE } from '../../shared/palette';
import { MAX_WIDTH, colorClasses, colorFor, widthFor } from './load-scale';
import { createBaseMap } from '../../shared/map/base-map';

/**
 * Every stretch between two stops on a map (grey base map), drawn by its peak-hour buses: as line width in proportion
 * ("width"), or as a sequential blue in equal classes ("color"). Hover a stretch for its numbers.
 */
@Component({
  selector: 'app-load-map',
  templateUrl: './load-map.html',
  styleUrl: './load-map.scss',
})
export class LoadMap {
  readonly segments = input.required<SegmentLoad[]>();
  readonly mode = input.required<'width' | 'color'>();
  /** e.g. "06:30–08:30 and 15:00–17:00", for tooltips. */
  readonly peakLabel = input('');

  private readonly mapEl = viewChild.required<ElementRef<HTMLDivElement>>('map');
  private map?: L.Map;
  private layer?: L.LayerGroup;
  private readonly ready = signal(false);

  protected readonly max = computed(() => Math.max(0, ...this.segments().map((s) => s.peakBuses)));
  protected readonly classes = computed(() => colorClasses(this.max()));
  /** Widths shown in the legend: a quarter, half and all of the busiest stretch. */
  protected readonly widthSamples = computed(() => {
    const max = this.max();
    return [0.25, 0.5, 1]
      .map((f) => Math.round(max * f))
      .map((v) => ({ value: v, width: widthFor(v, max) }));
  });
  protected readonly maxWidth = MAX_WIDTH;

  constructor() {
    afterNextRender(() => {
      this.map = createBaseMap(this.mapEl().nativeElement, { canvas: true, grey: true });
      this.layer = L.layerGroup().addTo(this.map);
      this.ready.set(true);
    });
    inject(DestroyRef).onDestroy(() => this.map?.remove());
    effect(() => {
      const segments = this.segments();
      if (this.ready()) this.draw(segments);
    });
  }

  private draw(segments: SegmentLoad[]): void {
    const layer = this.layer!;
    layer.clearLayers();
    const max = this.max();
    const classes = this.classes();
    const bounds = L.latLngBounds([]);
    // Quietest first, so busy stretches are drawn on top.
    for (const s of segments) {
      if (s.points.length < 2) continue;
      const style: L.PolylineOptions =
        this.mode() === 'width'
          ? { color: SEQUENTIAL_BLUE[3], weight: widthFor(s.peakBuses, max), opacity: 0.8, lineCap: 'butt' }
          : { color: colorFor(s.peakBuses, classes), weight: 4, opacity: 1 };
      L.polyline(s.points, style)
        .bindTooltip(
          `<strong>${escape(s.from)} – ${escape(s.to)}</strong><br>${s.peakBuses} buses in peak hours` +
            (this.peakLabel() ? ` (${this.peakLabel()})` : '') +
            `<br>${s.buses} a weekday, both directions`,
          { sticky: true },
        )
        .addTo(layer);
      // Frame the busier part of the network (the city); suburban ends are a zoom-out away.
      if (s.peakBuses >= max * 0.15) s.points.forEach((p) => bounds.extend(p));
    }
    if (bounds.isValid()) this.map!.fitBounds(bounds, { padding: [12, 12] });
  }
}

function escape(text: string): string {
  return text.replace(
    /[&<>"]/g,
    (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!,
  );
}
