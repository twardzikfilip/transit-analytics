import { AreaShape, LatLon } from './geo';

/** From GET /api/network-coverage. Areas in km². */

export interface CoverageShare {
  areaKm2: number;
  coveredKm2: number;
  /** coveredKm2 / areaKm2, 0–1. */
  share: number;
}

/** The city within `radiusMeters` of the nearest stop: all of it, and the part outside its woods. */
export interface RadiusCoverage {
  radiusMeters: number;
  city: CoverageShare;
  withoutForest: CoverageShare;
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

/** A district of the city, or a village or town around it (areas outside the city limits). */
export interface PlaceDensity {
  name: string;
  kind: 'district' | 'village' | 'town';
  /** The gmina of a village or town; null for a district. */
  gmina: string | null;
  shape: AreaShape;
  areaKm2: number;
  /** Null: no figure found. */
  population: number | null;
  /** When the figure is from, e.g. 2021-03-31 (the census). */
  populationDate: string | null;
  populationSource: string | null;
  /** Residents per km²; null without a figure. */
  density: number | null;
  forestKm2: number;
  /** It without its woods. */
  landKm2: number;
  landDensity: number | null;
  /** Woods cover at least `forestShare` of it. */
  largeForest: boolean;
  /** Share of it within each walking distance of a stop, as `coverages`, 0–1. */
  coveredShares: number[];
}

export interface Population {
  population: number;
  date: string;
  source: string;
}

/** Every village and town reaching within `distanceKm` of the city limits, and the woods in them. */
export interface Belt {
  distanceKm: number;
  cellMeters: number;
  places: PlaceDensity[];
  woods: AreaShape[];
}

export interface NetworkCoverage {
  stops: LatLon[];
  cellMeters: number;
  city: { name: string; shape: AreaShape; areaKm2: number };
  /** One per walking distance, shortest first. */
  coverages: RadiusCoverage[];
  forests: Woodland[];
  forestKm2: number;
  districts: PlaceDensity[];
  forestShare: number;
  /** The city's population by Statistics Poland: everyone living here, registered or not. */
  official: Population;
  sources: { shapes: string; shapesFetched: string; population: string };
  belt: Belt;
}
