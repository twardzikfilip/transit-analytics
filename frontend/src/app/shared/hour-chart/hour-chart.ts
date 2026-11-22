import { Component, computed, input, signal } from '@angular/core';
import { hostWidth } from '../host-width';
import { niceTicks, segments } from '../time-series-chart/chart-geometry';
import { clock, hourTickStep } from '../format/time';
import { t } from '../../core/i18n/t';
import { num } from '../format/number';

/** A point at an hour of the service day (fractional; 24+ is after midnight). */
export interface HourPoint {
  x: number;
  y: number;
}

export interface HourSeries {
  label: string;
  /** CSS colour, e.g. 'var(--chart-line)'. */
  color: string;
  points: HourPoint[];
  /** Fills towards 0 (a single-series chart). */
  area?: boolean;
}

/** A shaded range between two values, e.g. min–max. */
export interface HourBand {
  label: string;
  color: string;
  points: { x: number; lo: number; hi: number }[];
}

/** A horizontal reference line, e.g. a scheduled value. */
export interface RefLine {
  label: string;
  value: number;
}

const HEIGHT = 240;
const MARGIN = { top: 12, right: 16, bottom: 28, left: 44 };

/**
 * Values over the hours of a service day (SVG, no chart library): line series over an optional shaded band, dashed
 * reference lines, a legend, and a crosshair with a tooltip listing every series at the hovered hour (also with the
 * arrow keys). Lines break where an hour has no data.
 */
@Component({
  selector: 'app-hour-chart',
  templateUrl: './hour-chart.html',
  styleUrl: './hour-chart.scss',
  host: { '[class.loading]': 'loading()' },
})
export class HourChart {
  protected readonly t = t;
  readonly series = input<HourSeries[]>([]);
  readonly band = input<HourBand | null>(null);
  readonly refs = input<RefLine[]>([]);
  /** Visible hours, e.g. 4 to 26. */
  readonly from = input.required<number>();
  readonly to = input.required<number>();
  /** Spacing of the points in hours (1 = hourly), to find gaps and to label the hovered slot. */
  readonly step = input(1);
  readonly unit = input('');
  readonly format = input<(v: number) => string>((v) => num(v));
  readonly label = input('');
  readonly loading = input(false);
  /** Shown when there's nothing to draw; "Nothing recorded yet." by default. */
  readonly empty = input('');

  protected readonly width = hostWidth(600, 280);
  protected readonly height = HEIGHT;
  protected readonly margin = MARGIN;
  protected readonly hoverX = signal<number | null>(null);

  protected readonly plotRight = computed(() => this.width() - MARGIN.right);
  protected readonly plotBottom = HEIGHT - MARGIN.bottom;

  protected readonly hasData = computed(
    () => this.series().some((s) => s.points.length) || !!this.band()?.points.length,
  );

  protected readonly yTicks = computed(() => {
    const values = [
      ...this.series().flatMap((s) => s.points.map((p) => p.y)),
      ...(this.band()?.points.flatMap((p) => [p.lo, p.hi]) ?? []),
      ...this.refs().map((r) => r.value),
    ];
    return niceTicks(Math.max(0, ...values), 5, Math.min(0, ...values));
  });

  protected x(hour: number): number {
    const span = this.to() - this.from() || 1;
    return MARGIN.left + ((hour - this.from()) / span) * (this.plotRight() - MARGIN.left);
  }

  protected y(value: number): number {
    const ticks = this.yTicks();
    const lo = ticks[0];
    const span = ticks[ticks.length - 1] - lo || 1;
    return this.plotBottom - ((value - lo) / span) * (this.plotBottom - MARGIN.top);
  }

  protected readonly xTicks = computed(() => {
    const step = hourTickStep(this.to() - this.from(), Math.max(2, Math.floor(this.width() / 70)));
    const ticks: { x: number; text: string }[] = [];
    for (let h = Math.ceil(this.from() / step) * step; h <= this.to(); h += step) {
      ticks.push({ x: this.x(h), text: clock(h * 3600) });
    }
    return ticks;
  });

  /** Runs of consecutive points (as the shared geometry's time/value points), split at missing hours. */
  private runs<T extends { x: number }>(points: T[]): T[][] {
    const byTime = new Map(points.map((p) => [p.x, p]));
    return segments(
      points.map((p) => ({ time: p.x, value: 0 })),
      this.step(),
    ).map((run) => run.map((p) => byTime.get(p.time)!));
  }

  protected readonly paths = computed(() =>
    this.series().map((s) => {
      const runs = this.runs(s.points);
      const zero = this.y(0);
      return {
        color: s.color,
        lines: runs.map((r) =>
          r.map((p, i) => `${i ? 'L' : 'M'}${this.x(p.x)},${this.y(p.y)}`).join(''),
        ),
        areas: s.area
          ? runs.map(
              (r) =>
                `M${this.x(r[0].x)},${zero}` +
                r.map((p) => `L${this.x(p.x)},${this.y(p.y)}`).join('') +
                `L${this.x(r[r.length - 1].x)},${zero}Z`,
            )
          : [],
        dots: runs
          .filter((r) => r.length === 1)
          .map((r) => ({ cx: this.x(r[0].x), cy: this.y(r[0].y) })),
      };
    }),
  );

  protected readonly bandPaths = computed(() => {
    const band = this.band();
    if (!band) return [];
    return this.runs(band.points).map((r) => {
      // A single hour gets a small bar so it stays visible.
      const pts =
        r.length === 1
          ? [
              { ...r[0], x: r[0].x - 0.15 },
              { ...r[0], x: r[0].x + 0.15 },
            ]
          : r;
      return (
        pts.map((p, i) => `${i ? 'L' : 'M'}${this.x(p.x)},${this.y(p.hi)}`).join('') +
        [...pts]
          .reverse()
          .map((p) => `L${this.x(p.x)},${this.y(p.lo)}`)
          .join('') +
        'Z'
      );
    });
  });

  /** Every hour with data, for the crosshair to snap to. */
  private readonly xs = computed(() =>
    [
      ...new Set([
        ...this.series().flatMap((s) => s.points.map((p) => p.x)),
        ...(this.band()?.points.map((p) => p.x) ?? []),
      ]),
    ].sort((a, b) => a - b),
  );

  protected readonly hovered = computed(() => {
    const hx = this.hoverX();
    if (hx === null) return null;
    const x = this.x(hx);
    const half = this.step() / 2;
    const rows: { label: string; color: string; value: string; band?: boolean }[] = [];
    const band = this.band()?.points.find((p) => p.x === hx);
    const fmt = this.format();
    for (const s of this.series()) {
      const p = s.points.find((q) => q.x === hx);
      if (p) rows.push({ label: s.label, color: s.color, value: `${fmt(p.y)} ${this.unit()}` });
    }
    if (band) {
      rows.push({
        label: this.band()!.label,
        color: this.band()!.color,
        value: `${fmt(band.lo)}–${fmt(band.hi)} ${this.unit()}`,
        band: true,
      });
    }
    const ys = this.series()
      .map((s) => s.points.find((q) => q.x === hx)?.y)
      .filter((v): v is number => v !== undefined);
    return {
      x,
      y: ys.length ? this.y(Math.max(...ys)) : this.y(band?.hi ?? 0),
      markers: this.series()
        .map((s) => ({ color: s.color, p: s.points.find((q) => q.x === hx) }))
        .filter((m) => m.p)
        .map((m) => ({ color: m.color, cx: x, cy: this.y(m.p!.y) })),
      when: `${clock((hx - half) * 3600)}–${clock((hx + half) * 3600)}`,
      rows,
      left: x > this.width() * 0.6,
    };
  });

  protected readonly summary = computed(() =>
    t('charts.slots', { label: this.label() || t('charts.chart'), slots: this.xs().length }),
  );

  protected onPointerMove(event: PointerEvent): void {
    const rect = (event.currentTarget as SVGRectElement).ownerSVGElement!.getBoundingClientRect();
    const px = event.clientX - rect.left;
    const hour =
      this.from() +
      ((px - MARGIN.left) / (this.plotRight() - MARGIN.left)) * (this.to() - this.from());
    const xs = this.xs();
    if (!xs.length) return;
    this.hoverX.set(
      xs.reduce((best, v) => (Math.abs(v - hour) < Math.abs(best - hour) ? v : best), xs[0]),
    );
  }

  protected onKey(event: KeyboardEvent): void {
    const xs = this.xs();
    if (!xs.length || (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight')) return;
    event.preventDefault();
    const i = this.hoverX() === null ? -1 : xs.indexOf(this.hoverX()!);
    const next =
      event.key === 'ArrowRight'
        ? Math.min(xs.length - 1, i + 1)
        : Math.max(0, i < 0 ? xs.length - 1 : i - 1);
    this.hoverX.set(xs[next]);
  }
}
