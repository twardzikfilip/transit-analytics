import { DestroyRef, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import * as L from 'leaflet';
import { BusLaneApi } from '../../core/api/api.services';
import { BusLanes } from '../../core/models/models';
import { BUS_LANE_STYLE } from '../palette';

/** Leaflet pane above the city boundaries (350) and under the vehicles and lines (overlayPane, 400). */
const PANE = 'bus-lanes';

/** Draws every bus lane on `map`, beneath its other layers; they don't catch clicks or hovers. */
export function drawBusLanes(map: L.Map, lanes: BusLanes): L.LayerGroup {
  if (!map.getPane(PANE)) map.createPane(PANE).style.zIndex = '355';
  return L.layerGroup(
    lanes.lanes.map((lane) =>
      L.polyline(lane.points, {
        pane: PANE,
        interactive: false,
        color: BUS_LANE_STYLE.color,
        weight: BUS_LANE_STYLE.weight,
        opacity: BUS_LANE_STYLE.opacity,
        lineCap: 'butt',
      }),
    ),
  ).addTo(map);
}

/** Fetches the bus lanes and draws them on the map once it exists; call in a component's injection context. */
export function injectBusLanes(): (map: L.Map) => void {
  const api = inject(BusLaneApi);
  const destroyRef = inject(DestroyRef);
  return (map) =>
    api
      .lanes()
      .pipe(takeUntilDestroyed(destroyRef))
      .subscribe({ next: (lanes) => drawBusLanes(map, lanes), error: () => undefined });
}
