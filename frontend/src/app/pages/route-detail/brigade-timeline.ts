import { Component, computed, input, signal } from '@angular/core';
import { hostWidth } from '../../shared/host-width';
import { Brigade, LineVariant } from '../../core/models/models';
import { clock, duration, hourTickStep } from '../../shared/format/time';
import { hourRange } from './format';

const ROW = 26;
const BAR = 12;
const TOP = 22;
const LABEL = 72;
const RIGHT = 12;
let nextId = 0;

/** Bar colours per direction: categorical slots 1 and 3. */
export const DIRECTION_COLORS = ['var(--chart-line)', 'var(--chart-series-3)'];

interface Hover {
  x: number;
  y: number;
  title: string;
  detail: string;
  left: boolean;
}

/**
 * The day's brigades as a timeline: one row per bus, a bar per run (coloured by direction, yellow for a depot run,
 * hatched for a variant other than the base route), breaks of 30 minutes or more as a dashed, labelled gap, and the times the bus leaves
 * and returns to the depot at the row's ends.
 */
@Component({
  selector: 'app-brigade-timeline',
  templateUrl: './brigade-timeline.html',
  styleUrl: './brigade-timeline.scss',
})
export class BrigadeTimeline {
  readonly brigades = input.required<Brigade[]>();
  /** Base routes of the directions, for the legend. */
  readonly mains = input<LineVariant[]>([]);

  protected readonly width = hostWidth(800, 320);
  protected readonly hatchId = `hatch-${nextId++}`;
  protected readonly colors = DIRECTION_COLORS;
  protected readonly hover = signal<Hover | null>(null);
  protected readonly clock = clock;

  private readonly range = computed(() =>
    hourRange(this.brigades().flatMap((b) => [b.start, b.end])),
  );
  protected readonly height = computed(() => TOP + this.brigades().length * ROW + 4);
  protected readonly label = LABEL;
  protected readonly top = TOP;

  protected x(seconds: number): number {
    const { from, to } = this.range();
    return LABEL + ((seconds / 3600 - from) / (to - from)) * (this.width() - LABEL - RIGHT);
  }

  protected readonly ticks = computed(() => {
    const { from, to } = this.range();
    const step = hourTickStep(to - from, Math.max(2, Math.floor((this.width() - LABEL) / 56)));
    const ticks: { x: number; text: string }[] = [];
    for (let h = Math.ceil(from / step) * step; h <= to; h += step)
      ticks.push({ x: this.x(h * 3600), text: clock(h * 3600) });
    return ticks;
  });

  protected readonly rows = computed(() =>
    this.brigades().map((b, i) => {
      const mid = TOP + i * ROW + ROW / 2;
      return {
        b,
        mid,
        barY: mid - BAR / 2,
        startX: this.x(b.start),
        endX: this.x(b.end),
        hours: duration((b.end - b.start) / 60),
        runs: b.runs.map((r) => ({
          r,
          x: this.x(r.from),
          w: Math.max(1, this.x(r.to) - this.x(r.from) - 1),
          color: r.depot ? 'var(--depot)' : DIRECTION_COLORS[r.direction % 2],
        })),
        breaks: b.breaks.map((k) => ({
          k,
          x1: this.x(k.from),
          x2: this.x(k.to),
          text: duration((k.to - k.from) / 60),
        })),
      };
    }),
  );

  protected showRun(b: Brigade, r: Brigade['runs'][number], x: number, y: number): void {
    const kind = r.depot
      ? r.depot === 'pull_out'
        ? ' · depot run: from the depot into service'
        : ' · depot run: back to the depot'
      : r.kind === 'main'
        ? ''
        : ` · ${r.kind} variant`;
    this.hover.set({
      x,
      y,
      title: `${b.name}: ${clock(r.from)}–${clock(r.to)} (${duration((r.to - r.from) / 60)})`,
      detail: `towards ${r.headsign ?? '—'}${kind}`,
      left: x > this.width() * 0.6,
    });
  }

  protected showBreak(b: Brigade, k: Brigade['breaks'][number], x: number, y: number): void {
    this.hover.set({
      x,
      y,
      title: `${b.name}: break ${duration((k.to - k.from) / 60)}`,
      detail: `${clock(k.from)}–${clock(k.to)}, likely back at the depot`,
      left: x > this.width() * 0.6,
    });
  }

  protected showBrigade(b: Brigade, x: number, y: number): void {
    this.hover.set({
      x,
      y,
      title: `${b.name}: ${b.runs.length} runs, ${duration((b.end - b.start) / 60)} in service`,
      detail: `leaves the depot ${clock(b.start)}, returns ${clock(b.end)}`,
      left: x > this.width() * 0.6,
    });
  }
}
