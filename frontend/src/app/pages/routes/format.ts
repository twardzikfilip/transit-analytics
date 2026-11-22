import {
  LineRef,
  RouteCapacity,
  RouteGeometry,
  RouteSubtype,
  Segment,
  VariantKind,
} from '../../core/models/models';
import { localTime } from '../../shared/format/time';
import { t, tn } from '../../core/i18n/t';

/** Subtypes in display order, with their label and line colour (a CSS custom property from styles.scss). */
export const SUBTYPES: { subtype: RouteSubtype; color: string }[] = [
  { subtype: 'urban', color: '--line-urban' },
  { subtype: 'suburban', color: '--line-suburban' },
  { subtype: 'night_urban', color: '--line-night' },
  { subtype: 'regional', color: '--line-regional' },
];

/** "Urban", "Night", … in the language shown. */
export function subtypeLabel(subtype: RouteSubtype): string {
  return SUBTYPES.some((s) => s.subtype === subtype) ? t(`routes.subtype.${subtype}`) : subtype;
}

/** Geometries in display order (the table's columns); their names and meanings are routes.geometry(Desc).<key>. */
export const GEOMETRIES: { geometry: RouteGeometry }[] = [
  { geometry: 'radial' },
  { geometry: 'diameter' },
  { geometry: 'arcantial' },
  { geometry: 'tangential' },
  { geometry: 'feeder' },
];

/** Capacity classes, strongest first (the rules are configured in the backend); names in routes.capacity(Desc). */
export const CAPACITIES: { capacity: RouteCapacity }[] = [
  { capacity: 'main' },
  { capacity: 'primary' },
  { capacity: 'secondary' },
  { capacity: 'tertiary' },
  { capacity: 'irregular' },
];

export function geometryLabel(geometry: RouteGeometry | null): string {
  return geometry ? t(`routes.geometry.${geometry}`) : t('routes.notClassified');
}

/** What a geometry means, in a sentence. */
export function geometryDescription(geometry: RouteGeometry | null): string {
  return t(`routes.geometryDesc.${geometry ?? 'none'}`);
}

export function capacityLabel(capacity: RouteCapacity | null): string {
  return capacity ? t(`routes.capacity.${capacity}`) : t('routes.notClassified');
}

/** What a capacity class means, in a sentence. */
export function capacityDescription(capacity: RouteCapacity): string {
  return t(`routes.capacityDesc.${capacity}`);
}

/** A cell of the line table: one kind of line (a row) run in one geometry (a column). */
export interface LineTableRow {
  label: string;
  color: string;
  cells: LineRef[][];
}

/**
 * Lines in a table: a row per kind of line (urban, suburban, night, regional), a column per geometry, and a last
 * column for lines not classified, when there are any. Empty rows are left out; lines keep their order.
 */
export interface LineTable {
  columns: { label: string; description: string }[];
  rows: LineTableRow[];
}

export function lineTable(lines: LineRef[]): LineTable {
  const unclassified = lines.some((l) => l.geometry === null);
  const geometries: (RouteGeometry | null)[] = [
    ...GEOMETRIES.map((g) => g.geometry),
    ...(unclassified ? [null] : []),
  ];
  return {
    columns: geometries.map((g) => ({
      label: geometryLabel(g),
      description: geometryDescription(g),
    })),
    rows: SUBTYPES.map((s) => ({
      label: subtypeLabel(s.subtype),
      color: s.color,
      cells: geometries.map((g) =>
        lines.filter((l) => l.subtype === s.subtype && l.geometry === g),
      ),
    })).filter((r) => r.cells.some((c) => c.length > 0)),
  };
}

/** Indexes of the `count` slowest segments with a measured speed, slowest first. */
export function slowestSegments(segments: Segment[], count = 3): number[] {
  return segments
    .map((s, i) => ({ i, kmh: s.kmh }))
    .filter((s): s is { i: number; kmh: number } => s.kmh !== null)
    .sort((a, b) => a.kmh - b.kmh || a.i - b.i)
    .slice(0, count)
    .map((s) => s.i);
}

/** Whole hours around the given seconds-after-midnight times, with an hour of room on each side for labels. */
export function hourRange(times: number[]): { from: number; to: number } {
  if (!times.length) return { from: 4, to: 24 };
  return {
    from: Math.max(0, Math.floor(Math.min(...times) / 3600) - 1),
    to: Math.ceil(Math.max(...times) / 3600) + 1,
  };
}

/** What a variant is, in words. */
export function variantKindLabel(kind: VariantKind): string {
  return t(`routes.variant.${kind}`);
}

/**
 * The buses of one brigade's day in order, e.g. "224 until 17:19 → 221 17:26–19:44 → 036 from 19:50": the first
 * bus until it was replaced, the last from when it took over, those in between with both times.
 */
export function spellsText(spells: { number: string; from: string; to: string }[]): string {
  return spells
    .map((s, i) => {
      if (i === 0) return t('routes.spell.until', { bus: s.number, time: localTime(s.to) });
      if (i === spells.length - 1)
        return t('routes.spell.from', { bus: s.number, time: localTime(s.from) });
      return `${s.number} ${localTime(s.from)}–${localTime(s.to)}`;
    })
    .join(' → ');
}
