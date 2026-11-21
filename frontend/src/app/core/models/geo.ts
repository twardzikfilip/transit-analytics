/** A point as the backend sends it: [latitude, longitude]. */
export type LatLon = [number, number];

/** A polygon with holes: outer rings and holes, each a list of points. */
export interface AreaShape {
  outer: LatLon[][];
  inner: LatLon[][];
}
