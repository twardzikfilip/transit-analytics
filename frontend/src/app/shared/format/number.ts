import { intlLocale } from '../../core/i18n/t';

/**
 * Formatting of numbers, shares, distances, areas and densities, shared by the pages. Everything follows the chosen
 * language: "13,243.5" in English, "13 243,5" in Polish, German and French (each with its own grouping).
 */

const formats = new Map<string, Intl.NumberFormat>();

function format(digits: number, style: 'decimal' | 'percent' = 'decimal'): Intl.NumberFormat {
  const key = `${intlLocale()}|${digits}|${style}`;
  let f = formats.get(key);
  if (!f) {
    f = new Intl.NumberFormat(intlLocale(), {
      style,
      minimumFractionDigits: digits,
      maximumFractionDigits: digits,
    });
    formats.set(key, f);
  }
  return f;
}

/** A number with a fixed number of decimals, grouped: "19.8", "19,8" in Polish. */
export function decimal(n: number, digits = 1): string {
  return format(digits).format(n);
}

/** A number with up to `maxDigits` decimals, no trailing zeros: "2.5", "2,5" in Polish; "3". */
export function num(n: number, maxDigits = 1): string {
  const key = `${intlLocale()}|max${maxDigits}`;
  let f = formats.get(key);
  if (!f) {
    f = new Intl.NumberFormat(intlLocale(), { maximumFractionDigits: maxDigits });
    formats.set(key, f);
  }
  return f.format(n);
}

/** A whole number, grouped: "13,243", "13 243" in Polish. */
export function int(n: number): string {
  return decimal(Math.round(n), 0);
}

export interface PercentOptions {
  /** Decimals; 1 by default. */
  digits?: number;
  /** One decimal under 10%, whole percent from 10%, and "0%" for none (overrides `digits`). */
  adaptive?: boolean;
  /** A small but non-zero share shows as "<0.1%" rather than "0.0%". */
  tiny?: boolean;
}

/** A share (0–1) as a percentage: "74.0%", "74,0 %" in French. */
export function percent(share: number, options: PercentOptions = {}): string {
  if (options.tiny && share > 0 && share < 0.001) return `<${format(1, 'percent').format(0.001)}`;
  const digits = options.adaptive ? (share === 0 || share >= 0.1 ? 0 : 1) : (options.digits ?? 1);
  return format(digits, 'percent').format(share);
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
  return `${decimal(value, digits)} km`;
}

/** "17.6 km/h". */
export function kmh(value: number): string {
  return `${decimal(value)} km/h`;
}

/** "12.35 km²"; under 1 km² in hectares, "8.3 ha". */
export function area(km2: number): string {
  return km2 < 1 ? `${decimal(km2 * 100)} ha` : `${decimal(km2, 2)} km²`;
}

/** Residents per km²: "5,794 /km²". */
export function density(perKm2: number): string {
  return `${int(perKm2)} /km²`;
}
