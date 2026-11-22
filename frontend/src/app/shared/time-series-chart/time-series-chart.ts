import { Component, computed, input, signal } from '@angular/core';
import { hostWidth } from '../host-width';
import {
  ChartPoint,
  nearestIndex,
  niceTicks,
  segments,
  timeTicks,
  MIN_GAP_MS,
} from './chart-geometry';
import { t, tn } from '../../core/i18n/t';
import { intlLocale } from '../../core/i18n/t';

const HEIGHT = 260;
const DEFAULT_FORMAT = new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 });
const MARGIN = { top: 12, right: 16, bottom: 28, left: 44 };

/**
 * A single-series line chart over time (SVG, no chart library): 2px line over a 10% area wash, hairline grid,
 * a crosshair that snaps to the nearest bucket with a tooltip (also with the arrow keys), and breaks in the line
 * where no data was recorded.
 */
@Component({
  selector: 'app-time-series-chart',
  templateUrl: './time-series-chart.html',
  styleUrl: './time-series-chart.scss',
  host: { '[class.loading]': 'loading()' },
})
export class TimeSeriesChart {
  protected readonly t = t;
  /** Points sorted by time; only buckets with data. */
  readonly points = input.required<ChartPoint[]>();
  /** Visible time range (epoch ms). */
  readonly from = input.required<number>();
  readonly to = input.required<number>();
  /** Bucket size, used to detect gaps and to label the hovered bucket. */
  readonly stepMs = input.required<number>();
  /** Unit after a value in the tooltip, e.g. "vehicles". */
  readonly unit = input('');
  /** Formats a value for the tooltip (default: up to one decimal). */
  readonly format = input<(value: number) => string>((v) => DEFAULT_FORMAT.format(v));
  /** Formats a y axis tick (default: as a number). */
  readonly axisFormat = input<(value: number) => string>((v) => DEFAULT_FORMAT.format(v));
  /** Accessible name of the chart. */
  readonly label = input('');
  /** Dims the chart while new data loads, keeping the previous frame. */
  readonly loading = input(false);

  protected readonly width = hostWidth(600, 280);
  protected readonly height = HEIGHT;
  protected readonly margin = MARGIN;
  protected readonly hover = signal<number | null>(null);

  protected readonly plotRight = computed(() => this.width() - MARGIN.right);
  protected readonly plotBottom = HEIGHT - MARGIN.bottom;

  protected readonly yTicks = computed(() => {
    const values = this.points().map((p) => p.value);
    return niceTicks(Math.max(0, ...values), 5, Math.min(0, ...values));
  });
  private readonly yMin = computed(() => this.yTicks()[0]);
  private readonly yMax = computed(() => this.yTicks()[this.yTicks().length - 1]);
  /** Where 0 is: the area is filled towards it, so early (negative) values fill downwards. */
  protected readonly zeroY = computed(() => this.y(0));

  protected x(time: number): number {
    const span = this.to() - this.from() || 1;
    return MARGIN.left + ((time - this.from()) / span) * (this.plotRight() - MARGIN.left);
  }

  protected y(value: number): number {
    const span = this.yMax() - this.yMin() || 1;
    return this.plotBottom - ((value - this.yMin()) / span) * (this.plotBottom - MARGIN.top);
  }

  protected readonly xTicks = computed(() => {
    const { ticks, interval } = timeTicks(
      this.from(),
      this.to(),
      Math.max(2, Math.floor(this.width() / 90)),
    );
    const sameDay = new Date(this.from()).toDateString() === new Date(this.to() - 1).toDateString();
    const timeFmt = new Intl.DateTimeFormat(intlLocale(), { hour: '2-digit', minute: '2-digit' });
    const dayFmt = new Intl.DateTimeFormat(intlLocale(), { day: 'numeric', month: 'short' });
    return ticks.map((t) => {
      const d = new Date(t);
      const midnight = d.getHours() === 0 && d.getMinutes() === 0;
      // Days for long ranges; times otherwise, with the date at midnight when the range spans days.
      const text =
        interval >= 86_400_000 || (!sameDay && midnight) ? dayFmt.format(d) : timeFmt.format(d);
      return { x: this.x(t), text };
    });
  });

  /** Line paths per gap-free run; a run of one point is drawn as a dot so it isn't lost. */
  protected readonly runs = computed(() =>
    segments(this.points(), this.stepMs(), MIN_GAP_MS).map((run) => {
      const coords = run.map((p) => [this.x(p.time), this.y(p.value)] as const);
      const line = coords
        .map(([x, y], i) => `${i ? 'L' : 'M'}${x.toFixed(1)},${y.toFixed(1)}`)
        .join('');
      const base = this.zeroY().toFixed(1);
      const area =
        coords.length > 1
          ? `${line}L${coords[coords.length - 1][0].toFixed(1)},${base}L${coords[0][0].toFixed(1)},${base}Z`
          : null;
      return { line, area, single: coords.length === 1 ? coords[0] : null };
    }),
  );

  protected readonly hovered = computed(() => {
    const i = this.hover();
    const p = i === null ? undefined : this.points()[i];
    if (!p) return null;
    const timeFmt = new Intl.DateTimeFormat(intlLocale(), {
      weekday: 'short',
      day: 'numeric',
      month: 'short',
      hour: '2-digit',
      minute: '2-digit',
    });
    const endFmt = new Intl.DateTimeFormat(intlLocale(), { hour: '2-digit', minute: '2-digit' });
    const x = this.x(p.time);
    return {
      x,
      y: this.y(p.value),
      value: this.format()(p.value),
      when: `${timeFmt.format(p.time)}–${endFmt.format(p.time + this.stepMs())}`,
      // Keep the tooltip inside the chart: flip to the left of the crosshair in the right half.
      left: x > this.width() / 2,
    };
  });

  /** Plain-text summary for screen readers. */
  protected readonly summary = computed(() => {
    const pts = this.points();
    const label = this.label() || t('charts.chart');
    if (!pts.length) return t('charts.noData', { label });
    const values = pts.map((p) => p.value);
    const fmt = this.format();
    return t('charts.summary', {
      label,
      points: pts.length,
      min: fmt(Math.min(...values)),
      max: fmt(Math.max(...values)),
      unit: this.unit(),
    });
  });

  protected onPointerMove(event: PointerEvent): void {
    const svg =
      (event.currentTarget as SVGElement).ownerSVGElement ?? (event.currentTarget as SVGSVGElement);
    const rect = svg.getBoundingClientRect();
    const px = ((event.clientX - rect.left) / rect.width) * this.width();
    const time =
      this.from() +
      ((px - MARGIN.left) / (this.plotRight() - MARGIN.left)) * (this.to() - this.from());
    const i = nearestIndex(this.points(), time);
    this.hover.set(i >= 0 ? i : null);
  }

  protected onKey(event: KeyboardEvent): void {
    const n = this.points().length;
    if (!n) return;
    const current = this.hover() ?? (event.key === 'ArrowLeft' || event.key === 'End' ? n : -1);
    const next =
      event.key === 'ArrowRight'
        ? Math.min(n - 1, current + 1)
        : event.key === 'ArrowLeft'
          ? Math.max(0, current - 1)
          : event.key === 'Home'
            ? 0
            : event.key === 'End'
              ? n - 1
              : null;
    if (event.key === 'Escape') this.hover.set(null);
    if (next !== null) {
      event.preventDefault();
      this.hover.set(next);
    }
  }
}
