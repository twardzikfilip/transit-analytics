import { Component, ElementRef, effect, input, viewChild } from '@angular/core';
import * as L from 'leaflet';
import { NetworkCoverage, PlaceDensity } from '../../core/models/models';
import { escapeHtml } from '../../shared/format/html';
import { defineHatch, hatchFill, hatchSwatch, uniqueId } from '../../shared/map/hatch';
import { leafletHost } from '../../shared/map/leaflet-host';
import { labelPoint, rings } from '../../shared/map/shapes';
import {
  BOUNDARY_STYLES,
  DENSITY_NONE,
  DENSITY_SCALE,
  DENSITY_UNKNOWN,
  INK,
  WHITE,
} from '../../shared/palette';
import { densityColor, densityStepLabel, densityTooltip, labelMinKm2 } from './coverage-format';
import { t } from '../../core/i18n/t';

/** Panes, bottom to top: the places' fills, the woods, the outlines and the city limits. */
const PANES = { fills: 410, woods: 420, lines: 430 };

/**
 * The city's districts and the villages and towns around it, coloured by population density, light (few residents per
 * km²) to dark (many). The density is that outside a place's woods; the woods are on the scale too, as nobody lives
 * there: pale grey, lined, below the first step. Places without a figure are white with a dashed outline. Each place
 * is named as the zoom allows; hover one for its figures.
 */
@Component({
  selector: 'app-district-map',
  template: `
    <div #map class="leaflet-map" role="region" [attr.aria-label]="t('coverage.districtMap')"></div>
    <ul class="legend" [attr.aria-label]="t('coverage.perKm2')">
      <li class="muted">{{ t('coverage.perKm2Colon') }}</li>
      <li>
        <span class="swatch outlined" [style.background]="noneSwatch"></span
        >{{ t('coverage.zeroEmpty') }}
      </li>
      @for (s of scale; track s.min) {
        <li>
          <span class="swatch outlined" [style.background]="s.color"></span>{{ stepLabel(s) }}
        </li>
      }
      <li><span class="swatch unknown"></span>{{ t('coverage.noFigureLegend') }}</li>
    </ul>
  `,
  styles: `
    :host {
      display: block;
    }
    .leaflet-map ::ng-deep .place-name {
      background: transparent;
      border: none;
      box-shadow: none;
      color: ${INK};
      font-size: 0.72rem;
      font-weight: 600;
      text-shadow:
        0 0 3px ${WHITE},
        0 0 3px ${WHITE},
        0 0 2px ${WHITE};
      white-space: nowrap;
    }
    .leaflet-map ::ng-deep .place-name::before {
      display: none;
    }
    .swatch {
      width: 1.1rem;
    }
    .outlined {
      border: 1px solid rgb(0 0 0 / 15%);
    }
    .unknown {
      background: ${DENSITY_UNKNOWN.fill};
      border: 1px dashed ${DENSITY_UNKNOWN.line};
    }
    li {
      font-variant-numeric: tabular-nums;
    }
  `,
})
export class DistrictMap {
  protected readonly t = t;
  readonly data = input.required<NetworkCoverage>();

  protected readonly scale = DENSITY_SCALE;
  protected readonly stepLabel = densityStepLabel;
  protected readonly noneSwatch = hatchSwatch(DENSITY_NONE.fill, DENSITY_NONE.line);
  private readonly mapEl = viewChild.required<ElementRef<HTMLDivElement>>('map');
  private readonly host = leafletHost(() => this.mapEl().nativeElement, {
    grey: true,
    panes: PANES,
    zoomSnap: 0.25,
    setUp: (map) => map.on('zoomend', () => this.showLabels()),
  });
  private labels: { km2: number; label: L.Tooltip }[] = [];
  private readonly hatchId = uniqueId('no-people-hatch');

  constructor() {
    effect(() => {
      const data = this.data();
      if (this.host.ready()) this.draw(data);
    });
  }

  private draw(data: NetworkCoverage): void {
    const { map, layer } = this.host;
    layer.clearLayers();
    this.labels.forEach((l) => l.label.remove());
    this.labels = [];
    for (const p of [...data.districts, ...data.belt.places]) this.drawPlace(p);
    // Where nobody lives: the woods, and the Biała valley.
    const empty = [
      ...[...data.forests.flatMap((f) => f.shapes), ...data.belt.woods].map((shape) => ({
        shape,
        tip: t('coverage.woodsEmpty'),
      })),
      ...data.valley.map((v) => ({ shape: v.shape, tip: t('coverage.valleyEmpty') })),
    ];
    for (const { shape, tip } of empty) {
      L.polygon(rings(shape), {
        pane: 'woods',
        color: DENSITY_NONE.line,
        weight: 0.5,
        fillColor: hatchFill(this.hatchId),
        fillOpacity: 1,
      })
        .bindTooltip(tip, { sticky: true })
        .addTo(layer);
    }
    const svg = map.getPane('woods')?.querySelector('svg');
    if (svg) defineHatch(svg, this.hatchId, DENSITY_NONE.fill, DENSITY_NONE.line);
    const city = BOUNDARY_STYLES.city;
    const border = L.polygon(rings(data.city.shape), {
      pane: 'lines',
      color: city.color,
      dashArray: city.dashArray,
      weight: city.weight,
      fill: false,
      interactive: false,
    }).addTo(layer);
    // The whole belt around the city.
    const bounds = border.getBounds();
    for (const p of data.belt.places) {
      for (const ring of p.shape.outer) bounds.extend(L.latLngBounds(ring));
    }
    map.fitBounds(bounds, { padding: [12, 12] });
    this.showLabels();
  }

  /** A district, village or town: filled by its density (outside its woods), outlined, and named. */
  private drawPlace(p: PlaceDensity): void {
    const layer = this.host.layer;
    const known = p.landDensity !== null;
    L.polygon(rings(p.shape), {
      pane: 'fills',
      stroke: !known,
      color: DENSITY_UNKNOWN.line,
      weight: 1,
      dashArray: '3 3',
      fillColor: known ? densityColor(p.landDensity!) : DENSITY_UNKNOWN.fill,
      fillOpacity: known ? 0.8 : 0.6,
    })
      .bindTooltip(densityTooltip(p), { sticky: true })
      .addTo(layer);
    L.polygon(rings(p.shape), {
      pane: 'lines',
      color: WHITE,
      weight: p.kind === 'district' ? 1.5 : 1,
      opacity: 0.9,
      fill: false,
      interactive: false,
    }).addTo(layer);
    this.labels.push({
      km2: p.areaKm2,
      label: L.tooltip({
        permanent: true,
        direction: 'center',
        className: 'place-name',
        interactive: false,
      })
        .setLatLng(labelPoint(p.shape))
        .setContent(escapeHtml(p.name)),
    });
  }

  /** Names the places large enough not to crowd their neighbours at this zoom. */
  private showLabels(): void {
    const map = this.host.map;
    const min = labelMinKm2(map.getZoom());
    for (const { km2, label } of this.labels) {
      if (km2 >= min) {
        if (!map.hasLayer(label)) label.addTo(map);
      } else label.remove();
    }
  }
}
