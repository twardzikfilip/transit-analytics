import { Component, computed, input, output, signal } from '@angular/core';
import { hostWidth } from '../host-width';
import { num } from '../format/number';

/** One bar: its key (for selection), axis label, value, and tooltip lines. */
export interface Bar {
  key: string | number;
  label: string;
  value: number;
  /** Tooltip: a title and a line of detail. */
  title: string;
  detail?: string;
  /** Drawn with a warning mark above it (e.g. a day with incidents). */
  marked?: boolean;
}

const HEIGHT = 180;
const TOP = 14;
const BOTTOM = 22;
const LEFT = 40;
const RIGHT = 8;

/** "Nice" ticks for a value axis from 0 to max: 1, 2 or 5 × 10^n steps, at most `count` of them. */
export function valueTicks(max: number, count = 4): number[] {
  if (max <= 0) return [0];
  const rough = max / count;
  const power = 10 ** Math.floor(Math.log10(rough));
  const step = [1, 2, 5, 10].map((m) => m * power).find((s) => s >= rough) ?? 10 * power;
  const ticks: number[] = [];
  for (let v = 0; v <= max + step / 2; v += step) ticks.push(Number(v.toFixed(6)));
  return ticks;
}

/**
 * A single-series column chart: one thin bar per item in the given order, a recessive value axis and grid, a tooltip
 * per bar and, when there is room, labels under the bars (else every n-th). Bars can be clicked to select them.
 */
@Component({
  selector: 'app-bar-chart',
  template: `
    <div class="plot">
      <svg
        [attr.viewBox]="'0 0 ' + width() + ' ' + height"
        [attr.width]="width()"
        [attr.height]="height"
        role="img"
        [attr.aria-label]="label()"
        (pointerleave)="hover.set(null)"
      >
        @for (t of ticks(); track t.value) {
          <line
            class="grid"
            [attr.x1]="left"
            [attr.x2]="width() - right"
            [attr.y1]="t.y"
            [attr.y2]="t.y"
          />
          <text
            class="axis-label"
            [attr.x]="left - 6"
            [attr.y]="t.y"
            text-anchor="end"
            dominant-baseline="middle"
          >
            {{ t.text }}
          </text>
        }
        @for (b of geometry(); track b.bar.key) {
          <rect
            class="bar"
            [class.selected]="b.bar.key === selected()"
            [attr.x]="b.x"
            [attr.y]="b.y"
            [attr.width]="b.w"
            [attr.height]="b.h"
            rx="2"
          />
          @if (b.bar.marked) {
            <circle class="mark" [attr.cx]="b.x + b.w / 2" [attr.cy]="b.y - 6" r="3.5" />
          }
          @if (b.showLabel) {
            <text
              class="axis-label"
              [attr.x]="b.x + b.w / 2"
              [attr.y]="height - 6"
              text-anchor="middle"
            >
              {{ b.bar.label }}
            </text>
          }
          <rect
            class="hit"
            [class.clickable]="selectable()"
            [attr.x]="b.slotX"
            [attr.y]="top"
            [attr.width]="b.slotW"
            [attr.height]="height - top - bottom"
            (pointerenter)="hover.set(b)"
            (click)="selectable() && picked.emit(b.bar.key)"
          />
        }
      </svg>
      @if (hover(); as h) {
        <div
          class="chart-tooltip above"
          [style.left.px]="h.x + h.w / 2"
          [style.top.px]="h.y"
          aria-hidden="true"
        >
          <strong>{{ h.bar.title }}</strong>
          @if (h.bar.detail) {
            <span class="muted">{{ h.bar.detail }}</span>
          }
        </div>
      }
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
    .plot {
      position: relative;
    }
    svg {
      display: block;
      max-width: 100%;
      overflow: visible;
    }
    .bar {
      fill: var(--chart-line);
    }
    .bar.selected {
      fill: var(--chart-series-2);
    }
    .mark {
      fill: var(--danger);
      stroke: var(--surface);
      stroke-width: 2;
    }
    .hit {
      fill: transparent;
    }
    .hit.clickable {
      cursor: pointer;
    }
  `,
})
export class BarChart {
  readonly bars = input.required<Bar[]>();
  /** Describes the chart for screen readers; a table view should accompany it. */
  readonly label = input.required<string>();
  /** Formats axis values, e.g. hours. */
  readonly format = input<(value: number) => string>((v) => num(v, 2));
  readonly selected = input<string | number | null>(null);
  readonly selectable = input(false);
  readonly picked = output<string | number>();

  protected readonly width = hostWidth(800, 280);
  protected readonly height = HEIGHT;
  protected readonly top = TOP;
  protected readonly bottom = BOTTOM;
  protected readonly left = LEFT;
  protected readonly right = RIGHT;
  protected readonly hover = signal<ReturnType<BarChart['geometry']>[number] | null>(null);

  private readonly max = computed(() => Math.max(0, ...this.bars().map((b) => b.value)));
  private readonly scaleMax = computed(() => {
    const ticks = valueTicks(this.max());
    return Math.max(ticks[ticks.length - 1], this.max()) || 1;
  });

  private y(value: number): number {
    return HEIGHT - BOTTOM - (value / this.scaleMax()) * (HEIGHT - TOP - BOTTOM);
  }

  protected readonly ticks = computed(() =>
    valueTicks(this.max()).map((v) => ({ value: v, y: this.y(v), text: this.format()(v) })),
  );

  protected readonly geometry = computed(() => {
    const bars = this.bars();
    const plot = this.width() - LEFT - RIGHT;
    const slot = bars.length ? plot / bars.length : plot;
    // A 2px gap between bars when they are wide enough to keep one; at most 24px wide.
    const w = Math.max(1, Math.min(24, slot - (slot > 4 ? 2 : 0)));
    // Labels need about 32px each; show every n-th otherwise.
    const every = Math.max(1, Math.ceil(32 / slot));
    return bars.map((bar, i) => {
      const y = this.y(bar.value);
      return {
        bar,
        x: LEFT + i * slot + (slot - w) / 2,
        y,
        w,
        h: Math.max(0, HEIGHT - BOTTOM - y),
        slotX: LEFT + i * slot,
        slotW: slot,
        showLabel: i % every === 0,
      };
    });
  });
}
