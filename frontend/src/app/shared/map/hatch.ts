/** Fill patterns of horizontal lines for Leaflet polygons drawn as SVG (e.g. woods). */

/** Pixels between the lines. */
const GAP = 6;
let next = 0;

/** A new id, unique on the page, for a pattern (two maps on one page mustn't share one). */
export function uniqueId(prefix: string): string {
  return `${prefix}-${++next}`;
}

/** The fill value for polygons using a pattern. */
export function hatchFill(id: string): string {
  return `url(#${id})`;
}

/**
 * Adds, once, a pattern of horizontal lines to an SVG (Leaflet's renderer of a pane): a background of `fill` with
 * lines of `line`. Polygons filled with {@link hatchFill} then show it.
 */
export function defineHatch(svg: SVGSVGElement, id: string, fill: string, line: string): void {
  if (svg.querySelector(`#${id}`)) return;
  const ns = 'http://www.w3.org/2000/svg';
  const defs =
    svg.querySelector('defs') ??
    svg.insertBefore(document.createElementNS(ns, 'defs'), svg.firstChild);
  const pattern = document.createElementNS(ns, 'pattern');
  for (const [k, v] of Object.entries({
    id,
    patternUnits: 'userSpaceOnUse',
    width: GAP,
    height: GAP,
  })) {
    pattern.setAttribute(k, String(v));
  }
  const back = document.createElementNS(ns, 'rect');
  for (const [k, v] of Object.entries({ width: GAP, height: GAP, fill, 'fill-opacity': 0.85 })) {
    back.setAttribute(k, String(v));
  }
  const stroke = document.createElementNS(ns, 'line');
  for (const [k, v] of Object.entries({
    x1: 0,
    x2: GAP,
    y1: GAP / 2,
    y2: GAP / 2,
    stroke: line,
    'stroke-width': 1.2,
  })) {
    stroke.setAttribute(k, String(v));
  }
  pattern.append(back, stroke);
  defs.append(pattern);
}

/** CSS for a legend swatch showing the same pattern. */
export function hatchSwatch(fill: string, line: string): string {
  return `repeating-linear-gradient(to bottom, ${fill} 0 2px, ${line} 2px 3px, ${fill} 3px 5px)`;
}
