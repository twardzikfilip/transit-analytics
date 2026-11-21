import { PlaceDensity } from '../../core/models/models';
import { area, density, int } from '../../shared/format/number';
import { escapeHtml } from '../../shared/format/html';
import { DENSITY_SCALE } from '../../shared/palette';

/** The colour of a density on the scale. */
export function densityColor(perKm2: number): string {
  return (DENSITY_SCALE.find((s) => s.max === null || perKm2 < s.max) ?? DENSITY_SCALE[0]).color;
}

/** A step of the scale: "1 000–2 500", "10 000 and more". */
export function densityStepLabel(step: { min: number; max: number | null }): string {
  return step.max === null ? `${int(step.min)} and more` : `${int(step.min)}–${int(step.max)}`;
}

/** The smallest place (km²) named on the density map at a zoom level, so names don't crowd. */
export function labelMinKm2(zoom: number): number {
  return zoom >= 14 ? 0 : zoom >= 13 ? 1.2 : zoom >= 12 ? 2.6 : 6;
}

/** When a figure is from: "2021 census", "31.12.2023", or just the year. */
export function asOf(date: string | null): string {
  if (!date) return '';
  if (date === '2021-03-31') return '2021 census';
  const [y, m, d] = date.split('-');
  return d ? `${d}.${m}.${y}` : y;
}

/** What a place is called in a sentence. */
function kindWord(p: PlaceDensity): string {
  return p.kind === 'town' ? 'town' : p.kind === 'village' ? 'village' : 'district';
}

/**
 * A place's tooltip on the density map (HTML): residents, area, and densities with and without where nobody lives (its
 * woods and the Biała valley).
 */
export function densityTooltip(p: PlaceDensity): string {
  const what = kindWord(p);
  const title =
    `<strong>${escapeHtml(p.name)}</strong>` +
    (p.gmina ? ` <span>(${what}, ${escapeHtml(p.gmina)})</span>` : '');
  if (p.population === null || p.density === null || p.landDensity === null) {
    return `${title}<br>${area(p.areaKm2)}<br>No population figure found`;
  }
  const when = p.kind === 'district' ? '' : ` (${escapeHtml(asOf(p.populationDate))})`;
  const empty =
    p.forestKm2 > 0 && p.valleyKm2 > 0
      ? `the woods and the Biała valley (${area(p.forestKm2 + p.valleyKm2)})`
      : p.valleyKm2 > 0
        ? `the Biała valley (${area(p.valleyKm2)})`
        : `the woods (${area(p.forestKm2)})`;
  const densities =
    p.forestKm2 > 0 || p.valleyKm2 > 0
      ? `${density(p.landDensity)} outside ${empty}<br>` +
        `${density(p.density)} over the whole ${what}`
      : density(p.density);
  return `${title}<br>${int(p.population)} residents${when}, ${area(p.areaKm2)}<br>${densities}`;
}
