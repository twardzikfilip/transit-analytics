import {
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  effect,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import * as L from 'leaflet';
import { NetworkCoverage } from '../../core/models/models';
import { escapeHtml } from '../../shared/format/html';
import { createBaseMap } from '../../shared/map/base-map';
import { BOUNDARY_STYLES } from '../../shared/palette';
import {
  COVERED_COLOR,
  FOREST_FILL,
  FOREST_LINE,
  area,
  defineHatch,
  rings,
} from './coverage-format';

let hatches = 0;

/** A renderer on a pane: canvas (a thousand circles), or SVG where there is no canvas. */
function renderer(pane: string): L.Renderer {
  return L.Browser.canvas ? L.canvas({ pane }) : L.svg({ pane });
}

/**
 * The city limits, the area within walking distance of a stop (a circle around each, drawn as one area: the circles
 * are solid on a pane that is see-through as a whole, so overlaps don't darken), and the woods in light green with horizontal lines.
 */
@Component({
  selector: 'app-coverage-map',
  template: `
    <div
      #map
      class="map"
      role="region"
      aria-label="Map of the area near a stop and the woods"
    ></div>
    <ul class="legend">
      <li><span class="swatch covered"></span>Within {{ data().radiusMeters }} m of a stop</li>
      <li><span class="swatch forest"></span>Woods</li>
      <li><span class="swatch border"></span>{{ cityLabel }}</li>
      <li><span class="dot"></span>Stop</li>
    </ul>
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
      gap: 0.4rem 1.2rem;
      margin: 0;
      padding: 0.6rem 0 0;
      list-style: none;
      font-size: 0.85rem;
    }
    li {
      display: inline-flex;
      align-items: center;
      gap: 0.4rem;
    }
    .swatch {
      width: 1rem;
      height: 0.8rem;
      border-radius: 2px;
    }
    .covered {
      background: color-mix(in srgb, ${COVERED_COLOR} 35%, transparent);
    }
    .forest {
      background: repeating-linear-gradient(
        to bottom,
        ${FOREST_FILL} 0 2px,
        ${FOREST_LINE} 2px 3px,
        ${FOREST_FILL} 3px 5px
      );
      border: 1px solid ${FOREST_LINE};
    }
    .border {
      border-top: 3px dashed ${BOUNDARY_STYLES.city.color};
      height: 0;
    }
    .dot {
      width: 5px;
      height: 5px;
      border-radius: 50%;
      background: #1f2933;
    }
  `,
})
export class CoverageMap {
  readonly data = input.required<NetworkCoverage>();

  protected readonly cityLabel = BOUNDARY_STYLES.city.label;
  private readonly mapEl = viewChild.required<ElementRef<HTMLDivElement>>('map');
  private map?: L.Map;
  private layer?: L.LayerGroup;
  private readonly ready = signal(false);
  /** The woods' fill pattern, one per map so two maps on a page don't share an id. */
  private readonly hatchId = `forest-hatch-${++hatches}`;

  constructor() {
    afterNextRender(() => {
      const map = createBaseMap(this.mapEl().nativeElement, { grey: true });
      // Panes, bottom to top: the covered area (one see-through layer), the woods, the border, the stops.
      const covered = map.createPane('covered');
      covered.style.zIndex = '410';
      covered.style.opacity = '0.35';
      map.createPane('woods').style.zIndex = '420';
      map.createPane('border').style.zIndex = '430';
      map.createPane('stops').style.zIndex = '440';
      this.map = map;
      this.layer = L.layerGroup().addTo(map);
      this.ready.set(true);
    });
    inject(DestroyRef).onDestroy(() => this.map?.remove());
    effect(() => {
      const data = this.data();
      if (this.ready()) this.draw(data);
    });
  }

  /**
   * The woods' fill: light green with horizontal lines, so they read as woods also without colour (and in print).
   * Defined in the SVG the woods are drawn in, once.
   */
  private addHatch(): void {
    const svg = this.map!.getPane('woods')?.querySelector('svg');
    if (svg) defineHatch(svg, this.hatchId, FOREST_FILL, FOREST_LINE);
  }

  private draw(data: NetworkCoverage): void {
    const layer = this.layer!;
    layer.clearLayers();
    const coveredRenderer = renderer('covered');
    for (const [lat, lon] of data.stops) {
      L.circle([lat, lon], {
        radius: data.radiusMeters,
        renderer: coveredRenderer,
        stroke: false,
        fillColor: COVERED_COLOR,
        fillOpacity: 1,
        interactive: false,
      }).addTo(layer);
    }
    for (const forest of data.forests) {
      const tip = `<strong>${escapeHtml(forest.name)}</strong><br>${area(forest.areaKm2)}${
        forest.district && forest.named ? `, ${escapeHtml(forest.district)}` : ''
      }`;
      for (const shape of forest.shapes) {
        L.polygon(rings(shape), {
          pane: 'woods',
          color: FOREST_LINE,
          weight: 1,
          fillColor: `url(#${this.hatchId})`,
          fillOpacity: 1,
        })
          .bindTooltip(tip, { sticky: true })
          .addTo(layer);
      }
    }
    this.addHatch();
    const border = L.polygon(rings(data.city.shape), {
      pane: 'border',
      color: BOUNDARY_STYLES.city.color,
      dashArray: BOUNDARY_STYLES.city.dashArray,
      weight: 3,
      fill: false,
      interactive: false,
    }).addTo(layer);
    const stopsRenderer = renderer('stops');
    for (const [lat, lon] of data.stops) {
      L.circleMarker([lat, lon], {
        renderer: stopsRenderer,
        radius: 1.5,
        stroke: false,
        fillColor: '#1f2933',
        fillOpacity: 0.8,
        interactive: false,
      }).addTo(layer);
    }
    // Quarter zoom steps: the city just misses fitting at zoom 12, and 11 leaves it small.
    this.map!.options.zoomSnap = 0.25;
    this.map!.fitBounds(border.getBounds(), { padding: [12, 12] });
  }
}
