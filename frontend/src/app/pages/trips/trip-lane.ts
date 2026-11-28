import { Component, computed, input } from '@angular/core';
import { TripRun } from '../../core/models/models';
import { hostWidth } from '../../shared/host-width';
import { CHANGE_COLOR, NOT_RUN_COLOR, NOT_SEEN_COLOR } from '../../shared/palette';
import { axisTicks, timeScale, tripMarks } from './trip-layout';
import { t } from '../../core/i18n/t';

/** Space at the ends of every lane and of the axis above them, so their times route up. */
const PAD = 8;
const SCHED_Y = 4;
const ACTUAL_Y = 18;
const BAR_H = 10;
export const LANE_HEIGHT = 34;

/**
 * One trip on the list's shared time axis: its scheduled bar and, under it, what ran, coloured by the bus's delay
 * along the way. Parts not run are hatched red, parts not seen grey; where the bus stood still is outlined; a change
 * of bus (⇄), a turn short of the end (↩), a lost bus (✕) and a detour (↷) are marked.
 */
@Component({
  selector: 'app-trip-lane',
  template: `
    <svg [attr.width]="width()" [attr.height]="height" aria-hidden="true">
      @for (tick of ticks(); track tick.x) {
        <line class="grid" [attr.x1]="tick.x" [attr.x2]="tick.x" y1="0" [attr.y2]="height" />
      }
      @if (marks().connector; as c) {
        <line
          class="connector"
          [attr.x1]="c.from"
          [attr.y1]="SCHED_Y + BAR_H"
          [attr.x2]="c.to"
          [attr.y2]="ACTUAL_Y"
        />
      }
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
          [class.pending]="run().trip.kind === 'pending'"
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
          [attr.fill]="m.why === 'not-run' ? 'url(#lane-not-run)' : 'url(#lane-not-seen)'"
          [attr.stroke]="m.why === 'not-run' ? notRun : notSeen"
        />
      }
      @for (s of marks().stands; track $index) {
        <rect
          class="stand"
          [attr.x]="s.x - 1"
          [attr.width]="s.width + 2"
          [attr.y]="ACTUAL_Y - 2"
          [attr.height]="BAR_H + 4"
          rx="2"
        >
          <title>
            {{ t('trips.stoodTitle', { minutes: s.stand.minutes, stop: s.stand.stop }) }}
          </title>
        </rect>
      }
      @for (x of marks().changes; track $index) {
        <g class="marker" [attr.transform]="'translate(' + x + ',' + (ACTUAL_Y + BAR_H / 2) + ')'">
          <circle r="6" [attr.fill]="change" />
          <text y="3">⇄</text>
        </g>
      }
      @if (marks().end; as e) {
        <g
          class="marker"
          [attr.transform]="'translate(' + e.x + ',' + (ACTUAL_Y + BAR_H / 2) + ')'"
        >
          <circle r="6" [attr.fill]="notRun" />
          <text y="3">{{ e.kind === 'lost' ? '✕' : '↩' }}</text>
        </g>
      }
      @if (marks().detour !== null) {
        <g
          class="marker"
          [attr.transform]="'translate(' + marks().detour + ',' + (ACTUAL_Y + BAR_H / 2) + ')'"
        >
          <circle r="6" [attr.fill]="change" />
          <text y="3">↷</text>
        </g>
      }
    </svg>
  `,
  styles: `
    :host {
      display: block;
      min-width: 0;
    }
    svg {
      display: block;
    }
    .grid {
      stroke: var(--border);
    }
    .scheduled {
      fill: var(--border);
    }
    .actual {
      fill: var(--muted);
      fill-opacity: 0.35;
    }
    .actual.pending {
      fill-opacity: 0.15;
      stroke: var(--muted);
      stroke-dasharray: 3 3;
    }
    .stand {
      fill: none;
      stroke: var(--fg);
      stroke-width: 1.5;
    }
    .connector {
      stroke: var(--muted);
      stroke-dasharray: 2 2;
    }
    .marker text {
      fill: #fff;
      font-size: 9px;
      font-weight: 700;
      text-anchor: middle;
    }
  `,
})
export class TripLane {
  protected readonly t = t;
  readonly run = input.required<TripRun>();
  /** The list's time axis (epoch ms), the same for every lane. */
  readonly from = input.required<number>();
  readonly to = input.required<number>();

  protected readonly height = LANE_HEIGHT;
  protected readonly SCHED_Y = SCHED_Y;
  protected readonly ACTUAL_Y = ACTUAL_Y;
  protected readonly BAR_H = BAR_H;
  protected readonly notRun = NOT_RUN_COLOR;
  protected readonly notSeen = NOT_SEEN_COLOR;
  protected readonly change = CHANGE_COLOR;

  protected readonly width = hostWidth(500, 160);
  private readonly scale = computed(() =>
    timeScale(this.from(), this.to(), PAD, this.width() - PAD),
  );
  protected readonly ticks = computed(() => axisTicks(this.scale()));
  protected readonly marks = computed(() => tripMarks(this.run(), this.scale()));
}

/** The time labels over the lanes, on the same axis. */
@Component({
  selector: 'app-trip-axis',
  template: `
    <svg [attr.width]="width()" height="16" aria-hidden="true">
      @for (tick of ticks(); track tick.x) {
        <text [attr.x]="tick.x" y="12">{{ tick.label }}</text>
      }
    </svg>
  `,
  styles: `
    :host {
      display: block;
      min-width: 0;
    }
    svg {
      display: block;
      font-size: 11px;
      overflow: visible;
    }
    text {
      fill: var(--muted);
      text-anchor: middle;
    }
  `,
})
export class TripAxis {
  readonly from = input.required<number>();
  readonly to = input.required<number>();
  protected readonly width = hostWidth(500, 160);
  protected readonly ticks = computed(() =>
    axisTicks(timeScale(this.from(), this.to(), PAD, this.width() - PAD)),
  );
}

/** The hatch patterns the lanes use, once on the page. */
@Component({
  selector: 'app-trip-lane-patterns',
  template: `
    <svg width="0" height="0" aria-hidden="true" style="position: absolute">
      <defs>
        <pattern
          id="lane-not-run"
          width="6"
          height="6"
          patternUnits="userSpaceOnUse"
          patternTransform="rotate(45)"
        >
          <line x1="0" y1="0" x2="0" y2="6" [attr.stroke]="notRun" stroke-width="2.5" />
        </pattern>
        <pattern
          id="lane-not-seen"
          width="6"
          height="6"
          patternUnits="userSpaceOnUse"
          patternTransform="rotate(45)"
        >
          <line x1="0" y1="0" x2="0" y2="6" [attr.stroke]="notSeen" stroke-width="2" />
        </pattern>
      </defs>
    </svg>
  `,
})
export class TripLanePatterns {
  protected readonly notRun = NOT_RUN_COLOR;
  protected readonly notSeen = NOT_SEEN_COLOR;
}
