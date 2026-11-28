/**
 * Białystok's city limits and city centre (Osiedle Centrum) as closed rings of [latitude, longitude] pairs, from
 * OpenStreetMap (GET /api/boundaries).
 */
export interface CityBoundaries {
  city: [number, number][];
  centre: [number, number][];
  /** The smaller centre routes are classified by (geometry), set in the backend. */
  routeCentre: [number, number][];
}
