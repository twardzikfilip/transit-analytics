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
import { BeltPlace, NetworkCoverage } from '../../core/models/models';
import { escapeHtml } from '../../shared/format/html';
import { createBaseMap } from '../../shared/map/base-map';
import { BOUNDARY_STYLES } from '../../shared/palette';
import {
  DENSITY_CLASSES,
  NO_FIGURE_FILL,
  NO_FIGURE_LINE,
  NO_PEOPLE_FILL,
  NO_PEOPLE_LINE,
  area,
  defineHatch,
  density,
  densityClassLabel,
  densityColor,
  labelPoint,
  rings,
} from './coverage-format';

let hatches = 0;

/**
 * The city's districts, and the villages and towns around it, coloured by population density, on a scale light (few residents per km²) to dark (many). The
 * density is that of the district without its woods; the woods are on the scale too, as nobody lives there: pale
 * grey with grey lines, below the first step. Each district is named (as the zoom allows) and outlined; hover one for
 * its figures.
 */
@Component({
  selector: 'app-district-map',
  template: `
    <div
      #map
      class="map"
      role="region"
      aria-label="Map of the districts and the villages around the city by population density"
    ></div>
    <div class="scale" role="list" aria-label="Residents per km²">
      <span class="muted">Residents per km²:</span>
      <span class="step" role="listitem"><span class="swatch none"></span>0, woods</span>
      @for (c of classes; track c.min) {
        <span class="step" role="listitem">
          <span class="swatch" [style.background]="c.color"></span>{{ label(c) }}
        </span>
      }
      <span class="step" role="listitem"><span class="swatch unknown"></span>No figure</span>
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
    .map ::ng-deep .district-name {
      background: transparent;
      border: none;
      box-shadow: none;
      color: #1f2933;
      font-size: 0.72rem;
      font-weight: 600;
      text-shadow:
        0 0 3px #fff,
        0 0 3px #fff,
        0 0 2px #fff;
      white-space: nowrap;
    }
    .map ::ng-deep .district-name::before {
      display: none;
    }
    .scale {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.4rem 1rem;
      padding: 0.6rem 0 0;
      font-size: 0.85rem;
    }
    .step {
      display: inline-flex;
      align-items: center;
      gap: 0.35rem;
      font-variant-numeric: tabular-nums;
    }
    .swatch {
      width: 1.1rem;
      height: 0.8rem;
      border-radius: 2px;
      border: 1px solid rgb(0 0 0 / 15%);
    }
    .unknown {
      background: ${NO_FIGURE_FILL};
      border: 1px dashed ${NO_FIGURE_LINE};
    }
    .none {
      background: repeating-linear-gradient(
        to bottom,
        ${NO_PEOPLE_FILL} 0 2px,
        ${NO_PEOPLE_LINE} 2px 3px,
        ${NO_PEOPLE_FILL} 3px 5px
      );
    }
  `,
})
export class DistrictMap {
  readonly data = input.required<NetworkCoverage>();

  protected readonly classes = DENSITY_CLASSES;
  protected readonly label = densityClassLabel;
  private readonly mapEl = viewChild.required<ElementRef<HTMLDivElement>>('map');
  private map?: L.Map;
  private layer?: L.LayerGroup;
  private readonly ready = signal(false);
  private labels: { km2: number; label: L.Tooltip }[] = [];
  /** The woods' fill pattern, one per map so two maps on a page don't share an id. */
  private readonly hatchId = `no-people-hatch-${++hatches}`;

  constructor() {
    afterNextRender(() => {
      const map = createBaseMap(this.mapEl().nativeElement, { grey: true });
      // Panes, bottom to top: the districts' fills, the woods, the districts' outlines and the city limits.
      map.createPane('district-fills').style.zIndex = '410';
      map.createPane('district-woods').style.zIndex = '420';
      map.createPane('district-lines').style.zIndex = '430';
      this.map = map;
      this.layer = L.layerGroup().addTo(map);
      map.on('zoomend', () => this.showLabels());
      this.ready.set(true);
    });
    inject(DestroyRef).onDestroy(() => this.map?.remove());
    effect(() => {
      const data = this.data();
      if (this.ready()) this.draw(data);
    });
  }

  private draw(data: NetworkCoverage): void {
    const layer = this.layer!;
    layer.clearLayers();
    this.labels.forEach((l) => l.label.remove());
    this.labels = [];
    for (const d of data.districts) {
      const tip =
        `<strong>${escapeHtml(d.name)}</strong><br>` +
        `${d.population.toLocaleString('pl-PL')} residents, ${area(d.areaKm2)}<br>` +
        (d.forestKm2 > 0
          ? `${density(d.landDensity)} outside the woods (${area(d.forestKm2)})<br>` +
            `${density(d.density)} over the whole district`
          : density(d.density));
      L.polygon(rings(d.shape), {
        pane: 'district-fills',
        stroke: false,
        fillColor: densityColor(d.landDensity),
        fillOpacity: 0.8,
      })
        .bindTooltip(tip, { sticky: true })
        .addTo(layer);
      L.polygon(rings(d.shape), {
        pane: 'district-lines',
        color: '#ffffff',
        weight: 1.5,
        opacity: 0.9,
        fill: false,
        interactive: false,
      }).addTo(layer);
      this.labels.push({
        km2: d.areaKm2,
        label: L.tooltip({
          permanent: true,
          direction: 'center',
          className: 'district-name',
          interactive: false,
        })
          .setLatLng(labelPoint(d.shape))
          .setContent(escapeHtml(d.name)),
      });
    }
    for (const p of data.belt.places) this.drawPlace(p);
    for (const shape of data.belt.woods) {
      L.polygon(rings(shape), {
        pane: 'district-woods',
        color: NO_PEOPLE_LINE,
        weight: 0.5,
        fillColor: `url(#${this.hatchId})`,
        fillOpacity: 1,
      })
        .bindTooltip('Woods: no residents', { sticky: true })
        .addTo(layer);
    }
    for (const forest of data.forests) {
      for (const shape of forest.shapes) {
        L.polygon(rings(shape), {
          pane: 'district-woods',
          color: NO_PEOPLE_LINE,
          weight: 0.5,
          fillColor: `url(#${this.hatchId})`,
          fillOpacity: 1,
        })
          .bindTooltip(`<strong>${escapeHtml(forest.name)}</strong><br>Woods: no residents`, {
            sticky: true,
          })
          .addTo(layer);
      }
    }
    const svg = this.map!.getPane('district-woods')?.querySelector('svg');
    if (svg) defineHatch(svg, this.hatchId, NO_PEOPLE_FILL, NO_PEOPLE_LINE);
    const border = L.polygon(rings(data.city.shape), {
      pane: 'district-lines',
      color: BOUNDARY_STYLES.city.color,
      dashArray: BOUNDARY_STYLES.city.dashArray,
      weight: 3,
      fill: false,
      interactive: false,
    }).addTo(layer);
    // Quarter zoom steps: the city just misses fitting at zoom 12, and 11 leaves it small.
    this.map!.options.zoomSnap = 0.25;
    // The whole belt around the city.
    const bounds = border.getBounds();
    for (const p of data.belt.places)
      for (const ring of p.shape.outer) bounds.extend(L.latLngBounds(ring));
    this.map!.fitBounds(bounds, { padding: [12, 12] });
    this.showLabels();
  }

  /** A village or town around the city, like a district; one without a population figure white and dashed. */
  private drawPlace(p: BeltPlace): void {
    const known = p.population !== null && p.landDensity !== null && p.density !== null;
    const what = p.kind === 'town' ? 'town' : 'village';
    const tip =
      `<strong>${escapeHtml(p.name)}</strong> <span>(${what}, ${escapeHtml(p.gmina ?? '')})</span><br>` +
      (known
        ? `${p.population!.toLocaleString('pl-PL')} residents (${escapeHtml(p.populationDate ?? '')}), ` +
          `${area(p.areaKm2)}<br>` +
          (p.forestKm2 > 0
            ? `${density(p.landDensity!)} outside the woods (${area(p.forestKm2)})<br>` +
              `${density(p.density!)} over the whole ${what}`
            : density(p.density!))
        : `${area(p.areaKm2)}<br>No population figure found`);
    L.polygon(rings(p.shape), {
      pane: 'district-fills',
      stroke: !known,
      color: NO_FIGURE_LINE,
      weight: 1,
      dashArray: '3 3',
      fillColor: known ? densityColor(p.landDensity!) : NO_FIGURE_FILL,
      fillOpacity: known ? 0.8 : 0.6,
    })
      .bindTooltip(tip, { sticky: true })
      .addTo(this.layer!);
    L.polygon(rings(p.shape), {
      pane: 'district-lines',
      color: '#ffffff',
      weight: 1,
      opacity: 0.9,
      fill: false,
      interactive: false,
    }).addTo(this.layer!);
    this.labels.push({
      km2: p.areaKm2,
      label: L.tooltip({
        permanent: true,
        direction: 'center',
        className: 'district-name',
        interactive: false,
      })
        .setLatLng(labelPoint(p.shape))
        .setContent(escapeHtml(p.name)),
    });
  }

  /** Names the districts large enough not to crowd their neighbours at this zoom; every one from zoom 14. */
  private showLabels(): void {
    const map = this.map!;
    const min = labelMinKm2(map.getZoom());
    for (const { km2, label } of this.labels) {
      if (km2 >= min) {
        if (!map.hasLayer(label)) label.addTo(map);
      } else label.remove();
    }
  }
}

/** The smallest district or village named at a zoom level. */
export function labelMinKm2(zoom: number): number {
  return zoom >= 14 ? 0 : zoom >= 13 ? 1.2 : zoom >= 12 ? 2.6 : 6;
}
