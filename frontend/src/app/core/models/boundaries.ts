/**
 * Białystok's city limits and city centre (Osiedle Centrum) as closed rings of [latitude, longitude] pairs, from
 * OpenStreetMap (GET /api/boundaries).
 */
export interface CityBoundaries {
  city: [number, number][];
  centre: [number, number][];
  /** The smaller centre lines are classified by (geometry), set in the backend. */
  lineCentre: [number, number][];
}
