import { Component, ElementRef, effect, input, viewChild } from '@angular/core';
import * as L from 'leaflet';
import { NetworkCoverage } from '../../core/models/models';
import { area } from '../../shared/format/number';
import { escapeHtml } from '../../shared/format/html';
import { defineHatch, hatchFill, hatchSwatch, uniqueId } from '../../shared/map/hatch';
import { leafletHost, paneRenderer } from '../../shared/map/leaflet-host';
import { rings } from '../../shared/map/shapes';
import { BOUNDARY_STYLES, COVERAGE_STYLE, INK } from '../../shared/palette';
import { t } from '../../core/i18n/t';

/**
 * Panes, bottom to top: the covered area (one see-through layer, so overlaps don't darken), woods, the Biała valley,
 * border, stops.
 */
const PANES = { covered: 410, woods: 420, valley: 425, border: 430, stops: 440 };

/**
 * The city limits, the area within `radius` m of the nearest stop (a circle around each, drawn as one area: solid
 * circles on a pane that is see-through as a whole), the woods, light green with lines, and the uninhabited valley of
 * the river Biała, light blue with lines.
 */
@Component({
  selector: 'app-coverage-map',
  template: `
    <div
      #map
      class="leaflet-map"
      role="region"
      [attr.aria-label]="t('coverage.mapLabel', { m: radius() })"
    ></div>
    <ul class="legend">
      <li><span class="swatch covered"></span>{{ t('coverage.legendWithin', { m: radius() }) }}</li>
      <li>
        <span class="swatch" [style.background]="forestSwatch" [style.border]="forestBorder"></span
        >{{ t('coverage.woods') }}
      </li>
      <li>
        <span class="swatch" [style.background]="valleySwatch" [style.border]="valleyBorder"></span
        >{{ t('coverage.valley') }}
      </li>
      <li>
        <span class="line-swatch" [style.border-top-color]="border.color"></span
        >{{ t(border.label) }}
      </li>
      <li><span class="dot"></span>{{ t('coverage.stop') }}</li>
    </ul>
  `,
  styles: `
    :host {
      display: block;
    }
    .covered {
      background: color-mix(in srgb, ${COVERAGE_STYLE.covered} 35%, transparent);
    }
    .line-swatch {
      border-top-style: dashed;
    }
    .dot {
      width: 5px;
      height: 5px;
      border-radius: 50%;
      background: ${INK};
    }
  `,
})
export class CoverageMap {
  protected readonly t = t;
  readonly data = input.required<NetworkCoverage>();
  /** Walking distance to the nearest stop, in metres. */
  readonly radius = input.required<number>();

  protected readonly border = BOUNDARY_STYLES.city;
  protected readonly forestSwatch = hatchSwatch(
    COVERAGE_STYLE.forestFill,
    COVERAGE_STYLE.forestLine,
  );
  protected readonly forestBorder = `1px solid ${COVERAGE_STYLE.forestLine}`;
  protected readonly valleySwatch = hatchSwatch(
    COVERAGE_STYLE.valleyFill,
    COVERAGE_STYLE.valleyLine,
  );
  protected readonly valleyBorder = `1px solid ${COVERAGE_STYLE.valleyLine}`;
  private readonly mapEl = viewChild.required<ElementRef<HTMLDivElement>>('map');
  private readonly host = leafletHost(() => this.mapEl().nativeElement, {
    grey: true,
    panes: PANES,
    zoomSnap: 0.25,
    setUp: (map) => (map.getPane('covered')!.style.opacity = '0.35'),
  });
  private readonly hatchId = uniqueId('forest-hatch');
  private readonly valleyHatchId = uniqueId('valley-hatch');

  constructor() {
    effect(() => {
      const data = this.data();
      const radius = this.radius();
      if (this.host.ready()) this.draw(data, radius);
    });
  }

  private draw(data: NetworkCoverage, radius: number): void {
    const { map, layer } = this.host;
    layer.clearLayers();
    const covered = paneRenderer('covered');
    for (const [lat, lon] of data.stops) {
      L.circle([lat, lon], {
        radius,
        renderer: covered,
        stroke: false,
        fillColor: COVERAGE_STYLE.covered,
        fillOpacity: 1,
        interactive: false,
      }).addTo(layer);
    }
    for (const forest of data.forests) {
      const where = forest.district && forest.named ? `, ${escapeHtml(forest.district)}` : '';
      const tip = `<strong>${escapeHtml(forest.name)}</strong><br>${area(forest.areaKm2)}${where}`;
      for (const shape of forest.shapes) {
        L.polygon(rings(shape), {
          pane: 'woods',
          color: COVERAGE_STYLE.forestLine,
          weight: 1,
          fillColor: hatchFill(this.hatchId),
          fillOpacity: 1,
        })
          .bindTooltip(tip, { sticky: true })
          .addTo(layer);
      }
    }
    const svg = map.getPane('woods')?.querySelector('svg');
    if (svg) defineHatch(svg, this.hatchId, COVERAGE_STYLE.forestFill, COVERAGE_STYLE.forestLine);
    for (const stretch of data.valley) {
      const tip =
        `<strong>${escapeHtml(t('coverage.valleyTip', { name: stretch.name }))}</strong><br>` +
        escapeHtml(t('coverage.nobody', { area: area(stretch.areaKm2) }));
      L.polygon(rings(stretch.shape), {
        pane: 'valley',
        color: COVERAGE_STYLE.valleyLine,
        weight: 1,
        fillColor: hatchFill(this.valleyHatchId),
        fillOpacity: 1,
      })
        .bindTooltip(tip, { sticky: true })
        .addTo(layer);
    }
    const valleySvg = map.getPane('valley')?.querySelector('svg');
    if (valleySvg) {
      defineHatch(
        valleySvg,
        this.valleyHatchId,
        COVERAGE_STYLE.valleyFill,
        COVERAGE_STYLE.valleyLine,
      );
    }
    const border = L.polygon(rings(data.city.shape), {
      pane: 'border',
      color: this.border.color,
      dashArray: this.border.dashArray,
      weight: this.border.weight,
      fill: false,
      interactive: false,
    }).addTo(layer);
    const stops = paneRenderer('stops');
    for (const [lat, lon] of data.stops) {
      L.circleMarker([lat, lon], {
        renderer: stops,
        radius: 1.5,
        stroke: false,
        fillColor: INK,
        fillOpacity: 0.8,
        interactive: false,
      }).addTo(layer);
    }
    map.fitBounds(border.getBounds(), { padding: [12, 12] });
  }
}
