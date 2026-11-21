/** Formatting of numbers, shares, distances, areas and densities, shared by the pages. */

/** Numbers are grouped as in Poland: "13 243". */
export const NUMBER_LOCALE = 'pl-PL';

/** A whole number, grouped: "13 243". */
export function int(n: number): string {
  return Math.round(n).toLocaleString(NUMBER_LOCALE);
}

export interface PercentOptions {
  /** Decimals; 1 by default. */
  digits?: number;
  /** One decimal under 10%, whole percent from 10%, and "0%" for none (overrides `digits`). */
  adaptive?: boolean;
  /** A small but non-zero share shows as "<0.1%" rather than "0.0%". */
  tiny?: boolean;
}

/** A share (0–1) as a percentage: "74.0%". */
export function percent(share: number, options: PercentOptions = {}): string {
  const p = share * 100;
  if (options.tiny && share > 0 && share < 0.001) return '<0.1%';
  if (options.adaptive) return p === 0 ? '0%' : p < 10 ? `${p.toFixed(1)}%` : `${Math.round(p)}%`;
  return `${p.toFixed(options.digits ?? 1)}%`;
}

/** `part` of `whole` as a percentage, or `empty` ("–") when there is no whole. */
export function shareOf(
  part: number,
  whole: number,
  options: PercentOptions & { empty?: string } = {},
): string {
  return whole ? percent(part / whole, options) : (options.empty ?? '–');
}

/** "12.3 km". */
export function km(value: number, digits = 1): string {
  return `${value.toFixed(digits)} km`;
}

/** "17.6 km/h". */
export function kmh(value: number): string {
  return `${value.toFixed(1)} km/h`;
}

/** "12.35 km²"; under 1 km² in hectares, "8.3 ha". */
export function area(km2: number): string {
  return km2 < 1 ? `${(km2 * 100).toFixed(1)} ha` : `${km2.toFixed(2)} km²`;
}

/** Residents per km²: "5 794 /km²". */
export function density(perKm2: number): string {
  return `${int(perKm2)} /km²`;
}
