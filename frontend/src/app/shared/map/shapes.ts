import { AreaShape, LatLon } from '../../core/models/models';

/** Helpers for polygon shapes with holes, as the backend sends them. */

/** Every ring of a shape, outer and holes, for a Leaflet polygon. */
export function rings(shape: AreaShape): LatLon[][] {
  return [...shape.outer, ...shape.inner];
}

/** Where to put a name: the mean of the largest ring's points (inside for compact shapes). */
export function labelPoint(shape: AreaShape): LatLon {
  const ring = [...shape.outer].sort((a, b) => b.length - a.length)[0] ?? [];
  if (!ring.length) return [0, 0];
  const sum = ring.reduce(([a, b], [lat, lon]) => [a + lat, b + lon], [0, 0]);
  return [sum[0] / ring.length, sum[1] / ring.length];
}
