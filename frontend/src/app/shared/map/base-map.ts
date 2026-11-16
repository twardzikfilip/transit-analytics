import * as L from 'leaflet';

/** Centre of Białystok, where every map starts. */
export const BIALYSTOK: L.LatLngExpression = [53.13, 23.16];

/**
 * A Leaflet map of Białystok with the OpenStreetMap base layer.
 *
 * @param options.canvas draw vector layers on a canvas (faster with many markers or lines)
 * @param options.grey   grey base map (styled by the `grey-tiles` class), so coloured data stands out
 */
export function createBaseMap(
  el: HTMLElement,
  options: { canvas?: boolean; grey?: boolean } = {},
): L.Map {
  const map = L.map(el, { preferCanvas: options.canvas ?? false }).setView(BIALYSTOK, 12);
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; OpenStreetMap contributors',
    className: options.grey ? 'grey-tiles' : '',
  }).addTo(map);
  return map;
}
