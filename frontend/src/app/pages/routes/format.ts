import {
  LineRef,
  RouteCapacity,
  RouteGeometry,
  RouteSubtype,
  Segment,
  VariantKind,
} from '../../core/models/models';
import { localTime } from '../../shared/format/time';

/** Subtypes in display order, with their label and line colour (a CSS custom property from styles.scss). */
export const SUBTYPES: { subtype: RouteSubtype; label: string; color: string }[] = [
  { subtype: 'urban', label: 'Urban', color: '--line-urban' },
  { subtype: 'suburban', label: 'Suburban', color: '--line-suburban' },
  { subtype: 'night_urban', label: 'Night', color: '--line-night' },
  { subtype: 'regional', label: 'Regional', color: '--line-regional' },
];

export function subtypeLabel(subtype: RouteSubtype): string {
  return SUBTYPES.find((s) => s.subtype === subtype)?.label ?? subtype;
}

/** Geometries in display order (the table's columns), with what each means. */
export const GEOMETRIES: { geometry: RouteGeometry; label: string; description: string }[] = [
  { geometry: 'radial', label: 'Radial', description: 'Links the city centre with the periphery.' },
  {
    geometry: 'diameter',
    label: 'Diameter',
    description: 'Links one side of the city with the opposite side through the centre.',
  },
  {
    geometry: 'arcantial',
    label: 'Arcantial',
    description:
      'Two radial legs at an angle to each other (30°–160°), joined through or by the centre.',
  },
  {
    geometry: 'tangential',
    label: 'Tangential',
    description: 'Links outer parts of the city in an arc around the centre, without serving it.',
  },
  {
    geometry: 'feeder',
    label: 'Feeder',
    description: 'Like radial, but ends in the city short of the centre; mostly from the suburbs.',
  },
];

/** Capacity classes, strongest first, with what each means (the rules are configured in the backend). */
export const CAPACITIES: { capacity: RouteCapacity; label: string; description: string }[] = [
  {
    capacity: 'main',
    label: 'Main',
    description:
      'Articulated (MEGA) buses every day of the week, more often than every 15 min at peak.',
  },
  {
    capacity: 'primary',
    label: 'Primary',
    description: 'Partly articulated buses, more often than every 20 min at peak.',
  },
  {
    capacity: 'secondary',
    label: 'Secondary',
    description: 'More often than once an hour on average.',
  },
  {
    capacity: 'tertiary',
    label: 'Tertiary',
    description: 'Once an hour or less, but regular.',
  },
  {
    capacity: 'irregular',
    label: 'Irregular',
    description: 'A few trips a day, with large, uneven gaps.',
  },
];

export function geometryLabel(geometry: RouteGeometry | null): string {
  return GEOMETRIES.find((g) => g.geometry === geometry)?.label ?? 'Not classified';
}

export function capacityLabel(capacity: RouteCapacity | null): string {
  return CAPACITIES.find((c) => c.capacity === capacity)?.label ?? 'Not classified';
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
export function lineTable(lines: LineRef[]): {
  columns: { label: string; description: string }[];
  rows: LineTableRow[];
} {
  const unclassified = lines.some((l) => l.geometry === null);
  const geometries: (RouteGeometry | null)[] = [
    ...GEOMETRIES.map((g) => g.geometry),
    ...(unclassified ? [null] : []),
  ];
  return {
    columns: geometries.map((g) => ({
      label: geometryLabel(g),
      description:
        GEOMETRIES.find((x) => x.geometry === g)?.description ??
        "Other operators' lines: not classified.",
    })),
    rows: SUBTYPES.map((s) => ({
      label: s.label,
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
  return { main: 'Base route', longer: 'Longer', shorter: 'Shorter', depot: 'Depot run' }[kind];
}

/**
 * The buses of one brigade's day in order, e.g. "224 until 17:19 → 221 17:26–19:44 → 036 from 19:50": the first
 * bus until it was replaced, the last from when it took over, those in between with both times.
 */
export function spellsText(spells: { number: string; from: string; to: string }[]): string {
  return spells
    .map((s, i) => {
      if (i === 0) return `${s.number} until ${localTime(s.to)}`;
      if (i === spells.length - 1) return `${s.number} from ${localTime(s.from)}`;
      return `${s.number} ${localTime(s.from)}–${localTime(s.to)}`;
    })
    .join(' → ');
}
