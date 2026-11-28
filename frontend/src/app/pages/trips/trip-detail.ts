import { Component, computed, input, output, signal } from '@angular/core';
import { TripRun, TripRunDetail } from '../../core/models/models';
import { hostWidth } from '../../shared/host-width';
import { localTime } from '../../shared/format/time';
import { niceTicks, nearestIndex } from '../../shared/time-series-chart/chart-geometry';
import { CHANGE_COLOR, NOT_RUN_COLOR, NOT_SEEN_COLOR } from '../../shared/palette';
import { departureDelayText } from '../brigades/brigade-format';
import { axisTicks, dwellText, linePoints, timeScale, tripMarks, tripTimes } from './trip-layout';
import { t } from '../../core/i18n/t';

/** Room on the left for the lanes' names and the map button. */
const LEFT = 96;
const RIGHT = 16;
const MINUTE = 60_000;
/** Lanes, top to bottom: time labels, scheduled bar, actual bar, delay chart, speed chart. */
const AXIS_Y = 12;
const SCHED_Y = 22;
const ACTUAL_Y = 40;
const BAR_H = 12;
const DELAY = { top: 74, height: 110 };
const SPEED = { top: 214, height: 80 };
const HEIGHT = SPEED.top + SPEED.height + 8;

/**
 * One trip in detail, on its own time line: scheduled and actual bars (the actual one coloured by delay), and under
 * them the bus's delay and its speed over the whole trip, in two charts on the same time axis. Where a bus stood
 * still is shaded across all of them; a change of bus and an early end are marked. Hover for the values at a moment.
 * Each stop is a tick on the scheduled bar (when it was due) and a dot on the delay and speed routes (where the bus
 * passed it); hover one for its name and times, with a guide down the charts, to see between which stops delay grew
 * or the bus stood.
 * A button beside the actual bar shows the trip on a map, coloured by delay the same way.
 */
@Component({
  selector: 'app-trip-detail',
  template: `
    <button
      type="button"
      class="map-button"
      [class.active]="mapShown()"
      [style.top.px]="ACTUAL_Y - 4"
      [attr.aria-pressed]="mapShown()"
      [title]="t('trips.map.button')"
      (click)="toggleMap.emit()"
    >
      {{ t('trips.map.short') }}
    </button>
    <svg
      [attr.width]="width()"
      [attr.height]="height"
      role="img"
      [attr.aria-label]="t('trips.detail.label', { route: run().trip.route })"
      (mousemove)="hover($event)"
      (mouseleave)="hovered.set(null)"
    >
      <defs>
        <pattern
          [attr.id]="patternId('not-run')"
          width="6"
          height="6"
          patternUnits="userSpaceOnUse"
          patternTransform="rotate(45)"
        >
          <line x1="0" y1="0" x2="0" y2="6" [attr.stroke]="notRun" stroke-width="2.5" />
        </pattern>
        <pattern
          [attr.id]="patternId('not-seen')"
          width="6"
          height="6"
          patternUnits="userSpaceOnUse"
          patternTransform="rotate(45)"
        >
          <line x1="0" y1="0" x2="0" y2="6" [attr.stroke]="notSeen" stroke-width="2" />
        </pattern>
      </defs>
      @for (tick of ticks(); track tick.x) {
        <line
          class="grid"
          [attr.x1]="tick.x"
          [attr.x2]="tick.x"
          [attr.y1]="AXIS_Y + 4"
          [attr.y2]="height - 8"
        />
        <text class="tick" [attr.x]="tick.x" [attr.y]="AXIS_Y">{{ tick.label }}</text>
      }
      @for (st of marks().stands; track $index) {
        <rect
          class="stand"
          [attr.x]="st.x"
          [attr.width]="st.width"
          [attr.y]="SCHED_Y"
          [attr.height]="height - SCHED_Y - 8"
        />
      }
      <text class="lane" x="4" [attr.y]="SCHED_Y + 10">{{ t('trips.scheduled') }}</text>
      <text class="lane" x="4" [attr.y]="ACTUAL_Y + 10">{{ t('trips.ran') }}</text>
      <rect
        class="scheduled"
        [attr.x]="marks().scheduled.x"
        [attr.width]="marks().scheduled.width"
        [attr.y]="SCHED_Y"
        [attr.height]="BAR_H"
        rx="3"
      />
      @for (r of marks().runs; track $index) {
        <rect
          class="actual"
          [attr.x]="r.x"
          [attr.width]="r.width"
          [attr.y]="ACTUAL_Y"
          [attr.height]="BAR_H"
          rx="3"
        />
      }
      @for (d of marks().delays; track $index) {
        <rect
          [attr.x]="d.x"
          [attr.width]="d.width"
          [attr.y]="ACTUAL_Y"
          [attr.height]="BAR_H"
          [attr.fill]="d.color"
        />
      }
      @for (m of marks().missing; track $index) {
        <rect
          [attr.x]="m.x"
          [attr.width]="m.width"
          [attr.y]="ACTUAL_Y"
          [attr.height]="BAR_H"
          [attr.fill]="'url(#' + patternId(m.why) + ')'"
          [attr.stroke]="m.why === 'not-run' ? notRun : notSeen"
        />
      }
      <!-- Stops: when each was due, on the scheduled bar; when the bus passed it, on the charts below. -->
      @for (s of stopMarks(); track s.stop.index; let i = $index) {
        <line
          class="stop-tick"
          [attr.x1]="s.due"
          [attr.x2]="s.due"
          [attr.y1]="SCHED_Y - 2"
          [attr.y2]="SCHED_Y + BAR_H + 2"
        />
        <rect
          class="stop-hit"
          [attr.x]="s.due - 4"
          width="8"
          [attr.y]="SCHED_Y - 3"
          [attr.height]="BAR_H + 6"
          (mouseenter)="hoveredStop.set(i)"
          (mouseleave)="hoveredStop.set(null)"
        />
      }

      <!-- Delay, minutes -->
      <text class="lane" x="4" [attr.y]="DELAY.top - 6">{{ t('trips.detail.delay') }}</text>
      @for (tick of delayTicks(); track tick) {
        <line
          class="grid"
          [class.zero]="tick === 0"
          [attr.x1]="LEFT"
          [attr.x2]="width() - RIGHT"
          [attr.y1]="delayY(tick)"
          [attr.y2]="delayY(tick)"
        />
        <text class="value-tick" [attr.x]="LEFT - 4" [attr.y]="delayY(tick) + 4">{{ tick }}</text>
      }
      @for (route of delayLines(); track $index) {
        <polyline class="delay-line" [attr.points]="route" />
      }

      <!-- Speed, km/h -->
      <text class="lane" x="4" [attr.y]="SPEED.top - 6">{{ t('trips.detail.speed') }}</text>
      @for (tick of speedTicks(); track tick) {
        <line
          class="grid"
          [attr.x1]="LEFT"
          [attr.x2]="width() - RIGHT"
          [attr.y1]="speedY(tick)"
          [attr.y2]="speedY(tick)"
        />
        <text class="value-tick" [attr.x]="LEFT - 4" [attr.y]="speedY(tick) + 4">{{ tick }}</text>
      }
      @for (route of speedLines(); track $index) {
        <polyline class="speed-line" [attr.points]="route" />
      }

      <!-- Where the bus passed each stop, on the delay and speed lines. -->
      @for (s of stopMarks(); track s.stop.index; let i = $index) {
        @if (s.delayY !== null || s.speedY !== null) {
          <g
            class="stop-point"
            [class.hovered]="hoveredStop() === i"
            (mouseenter)="hoveredStop.set(i)"
            (mouseleave)="hoveredStop.set(null)"
          >
            @if (s.delayY !== null) {
              <circle class="hit" [attr.cx]="s.passed" [attr.cy]="s.delayY" r="7" />
              <circle class="dot" [attr.cx]="s.passed" [attr.cy]="s.delayY" r="3.5" />
            }
            @if (s.speedY !== null) {
              <circle class="hit" [attr.cx]="s.passed" [attr.cy]="s.speedY" r="7" />
              <circle class="dot" [attr.cx]="s.passed" [attr.cy]="s.speedY" r="3.5" />
            }
          </g>
        }
      }

      @for (x of marks().changes; track $index) {
        <line
          class="event"
          [attr.x1]="x"
          [attr.x2]="x"
          [attr.y1]="SCHED_Y"
          [attr.y2]="height - 8"
        />
        <g class="marker" [attr.transform]="'translate(' + x + ',' + (ACTUAL_Y - 2) + ')'">
          <circle r="7" [attr.fill]="change" />
          <text y="3.5">⇄</text>
        </g>
      }
      @if (marks().end; as e) {
        <line
          class="event end"
          [attr.x1]="e.x"
          [attr.x2]="e.x"
          [attr.y1]="SCHED_Y"
          [attr.y2]="height - 8"
        />
        <g
          class="marker"
          [attr.transform]="'translate(' + e.x + ',' + (ACTUAL_Y + BAR_H / 2) + ')'"
        >
          <circle r="7" [attr.fill]="notRun" />
          <text y="3.5">{{ e.kind === 'lost' ? '✕' : '↩' }}</text>
        </g>
      }
      @if (stopTip(); as s) {
        <line
          class="stop-guide"
          [attr.x1]="s.x"
          [attr.x2]="s.x"
          [attr.y1]="SCHED_Y"
          [attr.y2]="height - 8"
        />
      } @else if (tip(); as tip) {
        <line
          class="crosshair"
          [attr.x1]="tip.x"
          [attr.x2]="tip.x"
          [attr.y1]="SCHED_Y"
          [attr.y2]="height - 8"
        />
      }
    </svg>
    @if (stopTip(); as s) {
      <div class="tooltip" role="status" [style.left.px]="s.left">
        <strong>{{ s.name }}</strong>
        <span class="muted">{{
          t('trips.stops.index', { index: s.index, total: run().trip.stops })
        }}</span>
        <span>{{ t('trips.stops.due', { time: s.due }) }}</span>
        <span>{{
          s.passed
            ? t('trips.stops.passed', { time: s.passed, delay: s.delay })
            : t('trips.stops.notSeen')
        }}</span>
        @if (s.dwell) {
          <span>{{ s.dwell }}</span>
        }
      </div>
    } @else if (tip(); as tip) {
      <div class="tooltip" role="status" [style.left.px]="tip.left">
        <strong>{{ tip.time }}</strong>
        <span>{{ t('trips.detail.bus', { bus: tip.bus }) }}</span>
        <span>{{ t('trips.detail.delayAt', { delay: tip.delay }) }}</span>
        <span>{{ t('trips.detail.speedAt', { kmh: tip.kmh }) }}</span>
        <span class="muted">{{
          t('trips.detail.headingTo', {
            stop: tip.stopName,
            index: tip.stop,
            total: run().trip.stops,
          })
        }}</span>
      </div>
    }
  `,
  styles: `
    :host {
      position: relative;
      display: block;
    }
    svg {
      display: block;
      font-size: 11px;
    }
    .grid {
      stroke: var(--border);
      stroke-width: 1;
    }
    .grid.zero {
      stroke: var(--muted);
    }
    .tick {
      fill: var(--muted);
      text-anchor: middle;
    }
    .value-tick {
      fill: var(--muted);
      text-anchor: end;
    }
    .lane {
      fill: var(--muted);
      font-weight: 600;
    }
    .scheduled {
      fill: var(--border);
    }
    .actual {
      fill: var(--muted);
      fill-opacity: 0.3;
    }
    .stand {
      fill: var(--stand-color);
      fill-opacity: 0.18;
    }
    .delay-line,
    .speed-line {
      fill: none;
      stroke-width: 2;
      stroke-linejoin: round;
    }
    .delay-line {
      stroke: var(--chart-line);
    }
    .speed-line {
      stroke: var(--chart-series-3);
    }
    .event {
      stroke: var(--muted);
      stroke-dasharray: 3 3;
    }
    .marker text {
      fill: #fff;
      font-size: 10px;
      font-weight: 700;
      text-anchor: middle;
    }
    .stop-tick {
      stroke: var(--fg);
      stroke-width: 1.5;
    }
    .stop-hit,
    .stop-point .hit {
      fill: transparent;
      cursor: help;
    }
    .stop-point .dot {
      fill: var(--surface);
      stroke: var(--fg);
      stroke-width: 1.5;
      pointer-events: none;
    }
    .stop-point.hovered .dot {
      fill: var(--fg);
    }
    .stop-guide {
      stroke: var(--fg);
      stroke-dasharray: 2 3;
    }
    /* Guides follow the pointer; they mustn't take it from the stops under them. */
    .crosshair,
    .stop-guide,
    .event {
      pointer-events: none;
    }
    .crosshair {
      stroke: var(--fg);
      stroke-width: 1;
    }
    .map-button {
      position: absolute;
      left: 30px;
      padding: 0 0.4rem;
      font-size: 0.75rem;
      line-height: 1.3rem;
    }
    .tooltip {
      position: absolute;
      top: 0;
      z-index: 2;
      display: flex;
      flex-direction: column;
      gap: 0.1rem;
      padding: 0.4rem 0.6rem;
      font-size: 0.8rem;
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: var(--radius);
      box-shadow: var(--shadow-float);
      pointer-events: none;
    }
  `,
  host: { '[style.--stand-color]': 'change' },
})
export class TripDetail {
  protected readonly t = t;
  readonly run = input.required<TripRun>();
  /**
   * The trip in detail, once loaded: the speed route then follows every report (0 where the bus stood still), and the
   * stops tell how long it stood at each. Until then the speed is the per-minute samples'.
   */
  readonly detail = input<TripRunDetail | null>(null);
  /** Whether the trip's map is shown; the button next to the actual bar asks to show or hide it. */
  readonly mapShown = input(false);
  readonly toggleMap = output<void>();

  protected readonly height = HEIGHT;
  protected readonly AXIS_Y = AXIS_Y;
  protected readonly SCHED_Y = SCHED_Y;
  protected readonly ACTUAL_Y = ACTUAL_Y;
  protected readonly BAR_H = BAR_H;
  protected readonly DELAY = DELAY;
  protected readonly SPEED = SPEED;
  protected readonly LEFT = LEFT;
  protected readonly RIGHT = RIGHT;
  protected readonly notRun = NOT_RUN_COLOR;
  protected readonly notSeen = NOT_SEEN_COLOR;
  protected readonly change = CHANGE_COLOR;
  protected readonly hovered = signal<number | null>(null);

  protected readonly width = hostWidth(800, 320);
  private readonly scale = computed(() => {
    const r = this.run();
    const times = [...tripTimes(r), ...r.samples.map((p) => Date.parse(p.at))];
    return timeScale(
      Math.min(...times) - 2 * MINUTE,
      Math.max(...times) + 2 * MINUTE,
      LEFT,
      this.width() - RIGHT,
    );
  });
  protected readonly ticks = computed(() => axisTicks(this.scale()));
  protected readonly marks = computed(() => tripMarks(this.run(), this.scale()));

  /** Delay in minutes, early below zero. */
  protected readonly delayTicks = computed(() => {
    const minutes = this.run()
      .samples.map((p) => p.delay)
      .filter((d): d is number => d !== null)
      .map((d) => d / 60);
    return niceTicks(Math.max(1, ...minutes), 4, Math.min(0, ...minutes));
  });
  /** The speed route's pieces: every report's speed once the detail has them, else the per-minute samples'. */
  private readonly speedPoints = computed(() => {
    const detail = this.detail();
    return detail?.points.length
      ? linePoints(detail.points, (p) => p.kmh)
      : linePoints(this.run().samples, (p) => p.kmh);
  });
  protected readonly speedTicks = computed(() => {
    const kmh = this.speedPoints().flatMap((route) => route.map((p) => p.value));
    return niceTicks(Math.max(40, ...kmh), 4);
  });

  protected delayY(minutes: number): number {
    return panelY(DELAY, this.delayTicks(), minutes);
  }

  protected speedY(kmh: number): number {
    return panelY(SPEED, this.speedTicks(), kmh);
  }

  protected readonly delayLines = computed(() =>
    linePoints(this.run().samples, (p) => (p.delay === null ? null : p.delay / 60)).map((route) =>
      route.map((p) => `${this.scale().x(p.time)},${this.delayY(p.value)}`).join(' '),
    ),
  );
  protected readonly speedLines = computed(() =>
    this.speedPoints().map((route) =>
      route.map((p) => `${this.scale().x(p.time)},${this.speedY(p.value)}`).join(' '),
    ),
  );

  private readonly points = computed(() =>
    this.run().samples.map((p) => ({ time: Date.parse(p.at), value: 0 })),
  );

  protected readonly tip = computed(() => {
    const i = this.hovered();
    const p = i === null ? null : this.run().samples[i];
    if (!p) return null;
    const x = this.scale().x(p.at);
    return {
      x,
      left: Math.max(0, Math.min(x + 12, this.width() - 200)),
      time: localTime(p.at),
      bus: p.bus,
      delay: departureDelayText(p.delay),
      kmh: this.detail()
        ? roundOrDash(valueAt(this.speedPoints(), Date.parse(p.at)))
        : (p.kmh ?? '–'),
      stop: p.stop,
      stopName: this.detail()?.stops[p.stop - 1]?.name ?? '–',
    };
  });

  protected readonly hoveredStop = signal<number | null>(null);
  /** Each stop's place on the bars: when it was due, and when the bus passed it (null: not seen). */
  /**
   * Each stop's place: when it was due (on the scheduled bar), and where the bus passed it on the delay and speed
   * routes: the routes' value at that moment (null where they aren't drawn).
   */
  protected readonly stopMarks = computed(() => {
    const delays = linePoints(this.run().samples, (p) => (p.delay === null ? null : p.delay / 60));
    const speeds = this.speedPoints();
    return (this.detail()?.stops ?? []).map((stop) => {
      const passed = stop.passed ? Date.parse(stop.passed) : null;
      const delay = passed === null ? null : valueAt(delays, passed);
      const kmh = passed === null ? null : valueAt(speeds, passed);
      return {
        stop,
        due: this.scale().x(stop.scheduled),
        passed: passed === null ? null : this.scale().x(passed),
        delayY: delay === null ? null : this.delayY(delay),
        speedY: kmh === null ? null : this.speedY(kmh),
      };
    });
  });
  protected readonly stopTip = computed(() => {
    const i = this.hoveredStop();
    const m = i === null ? null : this.stopMarks()[i];
    if (!m) return null;
    const x = m.passed ?? m.due;
    return {
      x,
      left: Math.max(0, Math.min(x + 12, this.width() - 220)),
      name: m.stop.name,
      index: m.stop.index,
      due: localTime(m.stop.scheduled),
      passed: m.stop.passed ? localTime(m.stop.passed) : null,
      delay: departureDelayText(m.stop.delay),
      dwell: dwellText(m.stop.dwellSeconds),
    };
  });

  protected hover(event: MouseEvent): void {
    const svg = event.currentTarget as SVGSVGElement;
    const x = event.clientX - svg.getBoundingClientRect().left;
    const s = this.scale();
    const time = s.from + ((x - s.left) / (s.right - s.left)) * (s.to - s.from);
    const i = nearestIndex(this.points(), time);
    this.hovered.set(i < 0 ? null : i);
  }

  /** Each chart's patterns need their own ids (several trips can be open). */
  protected patternId(why: string): string {
    return `trip-${why}-${this.run().trip.tripId}-${this.run().serviceDay}`.replace(/[^\w-]/g, '_');
  }
}

/** Where a value falls in a chart panel whose axis runs over the ticks. */
function panelY(panel: { top: number; height: number }, ticks: number[], value: number): number {
  const lo = ticks[0];
  const hi = ticks[ticks.length - 1];
  return panel.top + panel.height - ((value - lo) / (hi - lo || 1)) * panel.height;
}

/**
 * A drawn route's value at a moment: between two of its points, the straight route between them; at a point, its
 * value; null where no route is drawn (before, after or between its pieces).
 */
export function valueAt(routes: { time: number; value: number }[][], time: number): number | null {
  for (const route of routes) {
    for (let i = 0; i < route.length; i++) {
      const a = route[i];
      if (a.time === time) return a.value;
      const b = route[i + 1];
      if (b && a.time < time && time < b.time) {
        return a.value + ((b.value - a.value) * (time - a.time)) / (b.time - a.time);
      }
    }
  }
  return null;
}

/** A value rounded, or "–" when there is none. */
function roundOrDash(value: number | null): number | string {
  return value === null ? '–' : Math.round(value);
}
