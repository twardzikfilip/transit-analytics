import { DestroyRef, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import * as L from 'leaflet';
import { BoundaryApi } from '../../core/api/api.services';
import { CityBoundaries } from '../../core/models/models';
import { BOUNDARY_STYLES } from '../palette';

/** Leaflet pane under the vehicle markers (overlayPane is at z-index 400). */
const PANE = 'city-boundaries';

/**
 * Draws the city limits and centre on `map`, beneath its other layers; they don't catch clicks or hovers. `centre`
 * picks which centre: Osiedle Centrum, or the smaller one lines are classified by.
 */
export function drawCityBoundaries(
  map: L.Map,
  boundaries: CityBoundaries,
  centre: 'centre' | 'lineCentre' = 'centre',
): L.LayerGroup {
  if (!map.getPane(PANE)) map.createPane(PANE).style.zIndex = '350';
  const layer = (ring: [number, number][], style: (typeof BOUNDARY_STYLES)['city' | 'centre']) =>
    L.polygon(ring, {
      pane: PANE,
      interactive: false,
      color: style.color,
      dashArray: style.dashArray,
      weight: 3,
      opacity: 0.9,
      fill: false,
    });
  return L.layerGroup([
    layer(boundaries.city, BOUNDARY_STYLES.city),
    layer(boundaries[centre], BOUNDARY_STYLES.centre),
  ]).addTo(map);
}

/** Fetches the boundaries and draws them on the map once it exists; call in a component's injection context. */
export function injectCityBoundaries(
  centre: 'centre' | 'lineCentre' = 'centre',
): (map: L.Map) => void {
  const api = inject(BoundaryApi);
  const destroyRef = inject(DestroyRef);
  return (map) =>
    api
      .boundaries()
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe({ next: (b) => drawCityBoundaries(map, b, centre), error: () => undefined });
}
