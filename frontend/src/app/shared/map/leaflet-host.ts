import { DestroyRef, Signal, afterNextRender, inject, signal } from '@angular/core';
import * as L from 'leaflet';
import { createBaseMap } from './base-map';

export interface LeafletHostOptions {
  /** Draw vector layers on a canvas (faster with many markers or lines). */
  canvas?: boolean;
  /** Grey base map, so coloured data stands out. */
  grey?: boolean;
  /** Extra panes and their z-index (the overlay pane is at 400, markers at 600). */
  panes?: Record<string, number>;
  /** Zoom steps; 0.25 lets a fitted area fill the map. Leaflet's default is 1. */
  zoomSnap?: number;
  /** Called once the map exists, before `ready` turns true (e.g. to add controls or listeners). */
  setUp?: (map: L.Map) => void;
}

/** A Leaflet map hosted by a component: created after the first render, removed with the component. */
export interface LeafletHost {
  /** True once the map exists. */
  readonly ready: Signal<boolean>;
  /** The map (only once `ready`). */
  readonly map: L.Map;
  /** A layer group on the map to draw into and clear. */
  readonly layer: L.LayerGroup;
}

/**
 * Creates a component's map: call in its constructor (injection context) with a getter for the map's element. Saves
 * every map component the same set-up: the base map, panes, a layer to draw into, and removing the map on destroy.
 */
export function leafletHost(
  element: () => HTMLElement,
  options: LeafletHostOptions = {},
): LeafletHost {
  const ready = signal(false);
  let map: L.Map | undefined;
  let layer: L.LayerGroup | undefined;
  afterNextRender(() => {
    map = createBaseMap(element(), { canvas: options.canvas, grey: options.grey });
    if (options.zoomSnap) map.options.zoomSnap = options.zoomSnap;
    for (const [name, zIndex] of Object.entries(options.panes ?? {})) {
      map.createPane(name).style.zIndex = String(zIndex);
    }
    layer = L.layerGroup().addTo(map);
    options.setUp?.(map);
    ready.set(true);
  });
  inject(DestroyRef).onDestroy(() => map?.remove());
  return {
    ready: ready.asReadonly(),
    get map() {
      return map!;
    },
    get layer() {
      return layer!;
    },
  };
}

/** A renderer on a pane: canvas where the browser has one (fast with many shapes), else SVG. */
export function paneRenderer(pane: string): L.Renderer {
  return L.Browser.canvas ? L.canvas({ pane }) : L.svg({ pane });
}
