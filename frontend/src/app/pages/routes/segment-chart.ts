import { Component, computed, input, signal } from '@angular/core';
import { hostWidth } from '../../shared/host-width';
import { SegmentProfile } from '../../core/models/models';
import { niceTicks } from '../../shared/time-series-chart/chart-geometry';
import { slowestSegments } from './format';
import { t as tr } from '../../core/i18n/t';
import { num } from '../../shared/format/number';

const HEIGHT = 220;
const MARGIN = { top: 22, right: 16, bottom: 28, left: 44 };

/**
 * Speed between consecutive stops along a route: one bar per segment, as wide as the segment is long (x = km from the
 * first stop), so the slow parts of the route stand out. The three slowest are highlighted and numbered; hover or
 * the arrow keys show each segment.
 */
@Component({
  selector: 'app-segment-chart',
  templateUrl: './segment-chart.html',
  styleUrl: './segment-chart.scss',
})
export class SegmentChart {
  protected readonly tr = tr;
  readonly profile = input.required<SegmentProfile>();

  protected readonly width = hostWidth(600, 280);
  protected readonly height = HEIGHT;
  protected readonly margin = MARGIN;
  protected readonly hover = signal<number | null>(null);

  protected readonly plotRight = computed(() => this.width() - MARGIN.right);
  protected readonly plotBottom = HEIGHT - MARGIN.bottom;
  private readonly firstKm = computed(() => this.profile().segments[0]?.fromKm ?? 0);
  private readonly lastKm = computed(() => {
    const s = this.profile().segments;
    return s.length ? s[s.length - 1].toKm : 1;
  });

  protected readonly slowest = computed(() => slowestSegments(this.profile().segments));
  protected readonly yTicks = computed(() =>
    niceTicks(Math.max(0, ...this.profile().segments.map((s) => s.kmh ?? 0)), 4),
  );

  protected x(km: number): number {
    const span = this.lastKm() - this.firstKm() || 1;
    return MARGIN.left + ((km - this.firstKm()) / span) * (this.plotRight() - MARGIN.left);
  }

  protected y(kmh: number): number {
    const max = this.yTicks()[this.yTicks().length - 1] || 1;
    return this.plotBottom - (kmh / max) * (this.plotBottom - MARGIN.top);
  }

  protected readonly xTicks = computed(() => {
    const span = this.lastKm() - this.firstKm();
    const ticks = niceTicks(span, Math.max(2, Math.floor(this.width() / 80)));
    return ticks
      .filter((t) => t <= span + 1e-9)
      .map((t) => ({ x: this.x(this.firstKm() + t), text: `${num(t, 2)} km` }));
  });

  /** One bar per segment with a 1px gap on each side; very short segments keep at least 1px. */
  protected readonly bars = computed(() =>
    this.profile().segments.map((s, i) => {
      const x0 = this.x(s.fromKm);
      const x1 = this.x(s.toKm);
      const w = Math.max(1, x1 - x0 - 2);
      const rank = this.slowest().indexOf(i);
      return {
        i,
        x: x0 + (x1 - x0 - w) / 2,
        w,
        y: s.kmh === null ? this.plotBottom : this.y(s.kmh),
        h: s.kmh === null ? 0 : this.plotBottom - this.y(s.kmh),
        rank: rank >= 0 ? rank + 1 : null,
        mid: (x0 + x1) / 2,
      };
    }),
  );

  protected readonly hovered = computed(() => {
    const i = this.hover();
    if (i === null) return null;
    const s = this.profile().segments[i];
    const bar = this.bars()[i];
    return {
      s,
      km: Math.round((s.toKm - s.fromKm) * 100) / 100,
      x: bar.mid,
      y: bar.y,
      left: bar.mid > this.width() * 0.6,
    };
  });

  protected readonly summary = computed(() => {
    const p = this.profile();
    const slow = this.slowest()
      .map((i) =>
        tr('speed.chartSlow', {
          from: p.segments[i].from,
          to: p.segments[i].to,
          kmh: p.segments[i].kmh,
        }),
      )
      .join('; ');
    return tr('speed.chartLabel', { to: p.headsign, slow: slow || tr('speed.noneMeasured') });
  });

  protected onPointerMove(event: PointerEvent): void {
    const rect = (event.currentTarget as SVGRectElement).ownerSVGElement!.getBoundingClientRect();
    const px = event.clientX - rect.left;
    const bars = this.bars();
    const hit = bars.find((b) => px >= b.x - 1 && px <= b.x + b.w + 1);
    this.hover.set(hit ? hit.i : null);
  }

  protected onKey(event: KeyboardEvent): void {
    const n = this.profile().segments.length;
    if (!n || (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight')) return;
    event.preventDefault();
    const i = this.hover();
    this.hover.set(
      event.key === 'ArrowRight' ? Math.min(n - 1, (i ?? -1) + 1) : Math.max(0, (i ?? n) - 1),
    );
  }
}
