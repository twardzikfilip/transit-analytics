/** Points are [latitude, longitude]; areas in km². From GET /api/network-coverage. */
export type LatLon = [number, number];

/** Outer rings and holes. */
export interface AreaShape {
  outer: LatLon[][];
  inner: LatLon[][];
}

export interface CoverageShare {
  areaKm2: number;
  coveredKm2: number;
  /** coveredKm2 / areaKm2, 0–1. */
  share: number;
}

export interface Woodland {
  /** Its name, or "Woodland in <district>" when unnamed. */
  name: string;
  named: boolean;
  /** The district most of it lies in; null for the smaller woods taken together. */
  district: string | null;
  areaKm2: number;
  pieces: number;
  shapes: AreaShape[];
}

export interface DistrictDensity {
  name: string;
  shape: AreaShape;
  areaKm2: number;
  /** Residents on the city's register, for permanent or temporary stay. */
  population: number;
  /** Residents per km². */
  density: number;
  forestKm2: number;
  /** The district without its woods. */
  landKm2: number;
  landDensity: number;
  /** Woods cover at least `forestShare` of it. */
  largeForest: boolean;
  /** Share of it within walking distance of a stop, 0–1. */
  coveredShare: number;
}

export interface NetworkCoverage {
  radiusMeters: number;
  stops: LatLon[];
  cellMeters: number;
  city: { name: string; shape: AreaShape; areaKm2: number };
  coverage: CoverageShare;
  withoutForest: CoverageShare;
  forests: Woodland[];
  forestKm2: number;
  districts: DistrictDensity[];
  forestShare: number;
  /** The city's population by Statistics Poland (GUS): everyone living here, registered or not. */
  officialPopulation: number;
  sources: { shapes: string; shapesFetched: string; population: string };
  belt: Belt;
}

/** A village (sołectwo) or town around the city; areas are outside the city limits. */
export interface BeltPlace {
  name: string;
  gmina: string | null;
  kind: 'village' | 'town';
  shape: AreaShape;
  areaKm2: number;
  /** Null: no figure found. */
  population: number | null;
  /** When the figure is from, e.g. 2021-03-31 (the census). */
  populationDate: string | null;
  populationSource: string | null;
  density: number | null;
  forestKm2: number;
  landKm2: number;
  landDensity: number | null;
  largeForest: boolean;
  coveredShare: number;
}

/** Every village and town reaching within `distanceKm` of the city limits, and the woods in them. */
export interface Belt {
  distanceKm: number;
  cellMeters: number;
  places: BeltPlace[];
  woods: AreaShape[];
}
