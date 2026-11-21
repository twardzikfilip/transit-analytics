import { AreaShape, LatLon } from '../../core/models/models';
import { SEQUENTIAL_BLUE } from '../../shared/palette';

/** Woods on the coverage map: light green with horizontal lines, outlined, in a darker green. */
export const FOREST_FILL = '#9fdc8f';
export const FOREST_LINE = '#4f9a45';
/** The area near a stop. */
export const COVERED_COLOR = '#2a78d6';

/** "74.0%". */
export function percent(share: number): string {
  return `${(share * 100).toFixed(1)}%`;
}

/** "12.35 km²"; small areas in hectares, "8.3 ha". */
export function area(km2: number): string {
  return km2 < 1 ? `${(km2 * 100).toFixed(1)} ha` : `${km2.toFixed(2)} km²`;
}

/** "5 794 /km²". */
export function density(perKm2: number): string {
  return `${Math.round(perKm2).toLocaleString('pl-PL')} /km²`;
}

/** Every ring of a shape, outer and holes, for Leaflet polygons. */
export function rings(shape: AreaShape): LatLon[][] {
  return [...shape.outer, ...shape.inner];
}

/** Where to put a district's name: the mean of its largest ring's points (inside for the city's compact districts). */
export function labelPoint(shape: AreaShape): LatLon {
  const ring = [...shape.outer].sort((a, b) => b.length - a.length)[0] ?? [];
  let n = 0;
  let lat = 0;
  let lon = 0;
  for (const [a, b] of ring) {
    lat += a;
    lon += b;
    n++;
  }
  return n ? [lat / n, lon / n] : [0, 0];
}

/**
 * Population density classes (residents per km²) for the district map, light to dark on the sequential blue scale;
 * `max` is exclusive, null for the last class.
 */
export const DENSITY_CLASSES: { min: number; max: number | null; color: string }[] = [
  { min: 0, max: 250, color: SEQUENTIAL_BLUE[0] },
  { min: 250, max: 1000, color: SEQUENTIAL_BLUE[1] },
  { min: 1000, max: 2500, color: SEQUENTIAL_BLUE[2] },
  { min: 2500, max: 5000, color: SEQUENTIAL_BLUE[3] },
  { min: 5000, max: 10000, color: SEQUENTIAL_BLUE[4] },
  { min: 10000, max: null, color: SEQUENTIAL_BLUE[5] },
];

/** Places without a population figure: white, outlined in dashed grey. */
export const NO_FIGURE_FILL = '#ffffff';
export const NO_FIGURE_LINE = '#8a8f98';

/** Woods on the density map: nobody lives there, below the scale's first step; pale grey with grey lines. */
export const NO_PEOPLE_FILL = '#f1f3f5';
export const NO_PEOPLE_LINE = '#9aa1a9';

/** The colour of a density. */
export function densityColor(perKm2: number): string {
  return (DENSITY_CLASSES.find((c) => c.max === null || perKm2 < c.max) ?? DENSITY_CLASSES[0])
    .color;
}

/** "1 000–2 500", "10 000 and more". */
export function densityClassLabel(c: { min: number; max: number | null }): string {
  const n = (x: number) => x.toLocaleString('pl-PL');
  return c.max === null ? `${n(c.min)} and more` : `${n(c.min)}–${n(c.max)}`;
}

/** Pixels between a hatch's horizontal lines. */
const HATCH_GAP = 6;

/**
 * Adds, once, a fill pattern of horizontal lines to an SVG (Leaflet's renderer of a pane), for polygons filled with
 * `url(#id)`: a background of `fill` with lines of `line`.
 */
export function defineHatch(svg: SVGSVGElement, id: string, fill: string, line: string): void {
  if (svg.querySelector(`#${id}`)) return;
  const ns = 'http://www.w3.org/2000/svg';
  const defs =
    svg.querySelector('defs') ??
    svg.insertBefore(document.createElementNS(ns, 'defs'), svg.firstChild);
  const pattern = document.createElementNS(ns, 'pattern');
  pattern.setAttribute('id', id);
  pattern.setAttribute('patternUnits', 'userSpaceOnUse');
  pattern.setAttribute('width', String(HATCH_GAP));
  pattern.setAttribute('height', String(HATCH_GAP));
  const back = document.createElementNS(ns, 'rect');
  back.setAttribute('width', String(HATCH_GAP));
  back.setAttribute('height', String(HATCH_GAP));
  back.setAttribute('fill', fill);
  back.setAttribute('fill-opacity', '0.85');
  const stroke = document.createElementNS(ns, 'line');
  stroke.setAttribute('x1', '0');
  stroke.setAttribute('x2', String(HATCH_GAP));
  stroke.setAttribute('y1', String(HATCH_GAP / 2));
  stroke.setAttribute('y2', String(HATCH_GAP / 2));
  stroke.setAttribute('stroke', line);
  stroke.setAttribute('stroke-width', '1.2');
  pattern.append(back, stroke);
  defs.append(pattern);
}
