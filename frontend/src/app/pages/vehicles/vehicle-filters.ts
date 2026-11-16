import { Vehicle } from '../../core/models/models';

export interface VehicleFilter {
  /** Empty string = any agency. */
  agency: string;
  /** First word of the model (usually the manufacturer); empty string = any. */
  make: string;
}

/** First word of a model name, e.g. "Solaris Urbino 12" → "Solaris". */
export function modelMake(model: string | null): string | null {
  const first = model?.trim().split(/\s+/)[0];
  return first ? first : null;
}

/** Distinct, sorted, non-empty values. */
export function options(values: (string | null)[]): string[] {
  return [...new Set(values.filter((v): v is string => !!v))].sort((a, b) => a.localeCompare(b));
}

export function filterVehicles(vehicles: Vehicle[], { agency, make }: VehicleFilter): Vehicle[] {
  return vehicles.filter((v) => (!agency || v.agency === agency) && (!make || modelMake(v.model) === make));
}
