import { Component, computed, input, signal } from '@angular/core';
import { clock, duration, hourTickStep } from '../../shared/format/time';
import { hostWidth } from '../../shared/host-width';
import { hourRange } from '../routes/format';
import { TimelineRow } from './fleet-format';
import { t as tr } from '../../core/i18n/t';

const ROW = 26;
const BAR = 12;
const TOP = 22;
const LABEL = 92;
const RIGHT = 12;

interface Hover {
  x: number;
  y: number;
  title: string;
  detail: string;
  left: boolean;
}

/**
 * A bus's runs as a timeline, like a brigade's on Routes: one row per day (or per run), a bar per run labelled with
 * its line when there is room, and breaks of 30 minutes or more as a dashed, labelled gap.
 */
@Component({
  selector: 'app-run-timeline',
  template: `
    <ul class="legend">
      <li>
        <span class="swatch" style="background: var(--lightblue-bkm)"></span
        >{{ tr('fleet.legendRun') }}
      </li>
      <li><span class="swatch dashed"></span>{{ tr('fleet.legendBreak') }}</li>
    </ul>
    <div class="plot">
      <svg
        [attr.viewBox]="'0 0 ' + width() + ' ' + height()"
        [attr.width]="width()"
        [attr.height]="height()"
        role="img"
        [attr.aria-label]="tr('fleet.timelineLabel', { n: rows().length })"
        (pointerleave)="hover.set(null)"
      >
        @for (t of ticks(); track t.x) {
          <line
            class="grid"
            [attr.x1]="t.x"
            [attr.x2]="t.x"
            [attr.y1]="top - 4"
            [attr.y2]="height()"
          />
          <text class="axis-label" [attr.x]="t.x" [attr.y]="top - 8" text-anchor="middle">
            {{ t.text }}
          </text>
        }
        @for (row of geometry(); track row.key) {
          <text class="name" x="0" [attr.y]="row.mid" dominant-baseline="middle">
            {{ row.label }}
          </text>
          @for (k of row.breaks; track k.x1) {
            <line
              class="break"
              [attr.x1]="k.x1"
              [attr.x2]="k.x2"
              [attr.y1]="row.mid"
              [attr.y2]="row.mid"
            />
            @if (k.x2 - k.x1 > 38) {
              <text
                class="break-label"
                [attr.x]="(k.x1 + k.x2) / 2"
                [attr.y]="row.mid - 4"
                text-anchor="middle"
              >
                {{ k.text }}
              </text>
            }
          }
          @for (r of row.runs; track r.x) {
            <rect
              class="run"
              [attr.x]="r.x"
              [attr.y]="row.mid - bar / 2"
              [attr.width]="r.w"
              [attr.height]="bar"
              rx="2"
              style="fill: var(--lightblue-bkm)"
              (pointerenter)="show(r, row.mid)"
            />
            @if (r.w > r.text.length * 7 + 6) {
              <text
                class="run-label"
                [attr.x]="r.x + 3"
                [attr.y]="row.mid"
                dominant-baseline="middle"
              >
                {{ r.text }}
              </text>
            }
          }
        }
      </svg>
      @if (hover(); as h) {
        <div
          class="chart-tooltip above"
          [class.left]="h.left"
          [style.left.px]="h.x"
          [style.top.px]="h.y"
          aria-hidden="true"
        >
          <strong>{{ h.title }}</strong>
          <span class="muted">{{ h.detail }}</span>
        </div>
      }
    </div>
  `,
  styleUrl: '../../shared/timeline/timeline.scss',
  styles: `
    .run-label {
      fill: #fff;
      font-size: 10px;
      pointer-events: none;
    }
  `,
})
export class RunTimeline {
  /** Translates (`t` is a loop variable in the template). */
  protected readonly tr = tr;
  readonly rows = input.required<TimelineRow[]>();

  protected readonly width = hostWidth(800, 320);
  protected readonly hover = signal<Hover | null>(null);
  protected readonly top = TOP;
  protected readonly bar = BAR;

  private readonly range = computed(() =>
    hourRange(this.rows().flatMap((r) => r.runs.flatMap((run) => [run.from, run.to]))),
  );
  protected readonly height = computed(() => TOP + this.rows().length * ROW + 4);

  private x(seconds: number): number {
    const { from, to } = this.range();
    return LABEL + ((seconds / 3600 - from) / (to - from)) * (this.width() - LABEL - RIGHT);
  }

  protected readonly ticks = computed(() => {
    const { from, to } = this.range();
    const step = hourTickStep(to - from, Math.max(2, Math.floor((this.width() - LABEL) / 56)));
    const ticks: { x: number; text: string }[] = [];
    for (let h = Math.ceil(from / step) * step; h <= to; h += step) {
      ticks.push({ x: this.x(h * 3600), text: clock(h * 3600) });
    }
    return ticks;
  });

  protected readonly geometry = computed(() =>
    this.rows().map((row, i) => ({
      key: row.key,
      label: row.label,
      mid: TOP + i * ROW + ROW / 2,
      runs: row.runs.map((r) => ({
        ...r,
        x: this.x(r.from),
        w: Math.max(2, this.x(r.to) - this.x(r.from)),
      })),
      breaks: row.breaks.map((k) => ({
        x1: this.x(k.from),
        x2: this.x(k.to),
        text: duration((k.to - k.from) / 60),
      })),
    })),
  );

  protected show(r: { x: number; w: number; title: string; detail: string }, y: number): void {
    const x = r.x + r.w / 2;
    this.hover.set({ x, y, title: r.title, detail: r.detail, left: x > this.width() * 0.6 });
  }
}
