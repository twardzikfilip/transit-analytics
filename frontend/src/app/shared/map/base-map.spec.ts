import * as L from 'leaflet';
import { BIALYSTOK, createBaseMap } from './base-map';

describe('createBaseMap', () => {
  it('centres on Białystok with the OpenStreetMap layer, optionally grey', () => {
    const el = document.createElement('div');
    document.body.appendChild(el);
    const map = createBaseMap(el, { grey: true });

    expect(map.getCenter()).toEqual(L.latLng(BIALYSTOK));
    expect(map.getZoom()).toBe(12);
    const layers: L.TileLayer[] = [];
    map.eachLayer((l) => layers.push(l as L.TileLayer));
    expect(layers).toHaveLength(1);
    expect(layers[0].options.className).toBe('grey-tiles');
    expect(layers[0].getAttribution?.()).toContain('OpenStreetMap');
    map.remove();
    el.remove();
  });
});
