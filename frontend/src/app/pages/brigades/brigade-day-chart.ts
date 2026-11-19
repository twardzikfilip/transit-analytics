import { Component, computed, input, output, signal } from '@angular/core';
import { BrigadeTrip } from '../../core/models/models';
import { hostWidth } from '../../shared/host-width';
import { localTime } from '../../shared/format/time';
import {
  CHANGE_COLOR,
  KIND_LABELS,
  NOT_RUN_COLOR,
  NOT_SEEN_COLOR,
  TRIP_COLORS,
  actualSpan,
  busesText,
  delayText,
  shortStop,
  span,
  tripLabel,
} from './brigade-format';

const LEFT = 78;
const RIGHT = 16;
/** Lanes, top to bottom: hour labels, scheduled labels (two rows), scheduled bars, actual bars, bus labels. */
const AXIS_Y = 14;
const SCHED_LABEL_Y = [34, 48];
const SCHED_Y = 56;
const BAR_H = 16;
/** Actual runs in two rows, consecutive trips alternating, so a late trip running into the next stays clear. */
const ACTUAL_Y = [96, 116];
const BUS_LABEL_Y = [146, 160];
export const CHART_HEIGHT = 170;
/** At least this many px per minute: a full day scrolls sideways rather than squeezing the trips. */
export const MIN_PX_PER_MIN = 1.6;
/** About this many px per character of an 11 px label. */
const CHAR_PX = 6.3;
const MINUTE = 60_000;

/** A label cut to fit a width, with an ellipsis; empty when not even a few characters fit. */
export function fit(text: string, px: number): string {
  const chars = Math.floor(px / CHAR_PX);
  if (chars >= text.length) return text;
  return chars < 4 ? '' : text.slice(0, chars - 1) + '…';
}

interface Bar {
  x: number;
  width: number;
}

/**
 * A brigade's day on one time line: each scheduled trip as a bar (consecutive trips alternating in colour, labelled
 * with line and destination), and under it what actually ran, in the same colour, a bar per bus (labelled with the
 * bus and how late it set off). Parts nobody ran are hatched red, parts no bus was seen on grey; trips without data
 * are outlined. A thin line joins each scheduled start to the actual one, so lateness shows as a slant; markers flag
 * a change of bus, a lost trip and a detour. Hover a trip for its details; click to choose it.
 */
@Component({
  selector: 'app-brigade-day-chart',
  template: `
    <div class="scroll">
      <svg
        [attr.width]="width()"
        [attr.height]="height"
        role="img"
        [attr.aria-label]="'Brigade timeline: ' + trips().length + ' trips, scheduled and actual'"
        (mouseleave)="hovered.set(null)"
      >
        <defs>
          <pattern
            id="not-run"
            width="6"
            height="6"
            patternUnits="userSpaceOnUse"
            patternTransform="rotate(45)"
          >
            <rect width="6" height="6" [attr.fill]="notRun" fill-opacity="0.15" />
            <line x1="0" y1="0" x2="0" y2="6" [attr.stroke]="notRun" stroke-width="2.5" />
          </pattern>
          <pattern
            id="not-seen"
            width="6"
            height="6"
            patternUnits="userSpaceOnUse"
            patternTransform="rotate(45)"
          >
            <line x1="0" y1="0" x2="0" y2="6" [attr.stroke]="notSeen" stroke-width="2" />
          </pattern>
        </defs>

        @for (t of ticks(); track t.x) {
          <line
            class="grid"
            [attr.x1]="t.x"
            [attr.x2]="t.x"
            [attr.y1]="AXIS_Y + 4"
            [attr.y2]="height - 4"
          />
          <text class="tick" [attr.x]="t.x" [attr.y]="AXIS_Y">{{ t.label }}</text>
        }
        <text class="lane" x="4" [attr.y]="SCHED_Y + 13">Scheduled</text>
        <text class="lane" x="4" [attr.y]="ACTUAL_Y[0] + 22">Actual</text>

        @for (row of rows(); track row.trip.tripId; let i = $index) {
          <g
            class="trip"
            [class.chosen]="selected() === i"
            [class.dim]="selected() !== null && selected() !== i"
            (mouseenter)="hovered.set(i)"
            (click)="choose.emit(i)"
          >
            <rect
              class="hit"
              [attr.x]="row.hit.x"
              [attr.width]="row.hit.width"
              [attr.y]="SCHED_LABEL_Y[0] - 12"
              [attr.height]="BUS_LABEL_Y[1] - SCHED_LABEL_Y[0] + 16"
            />
            <!-- Scheduled -->
            <rect
              class="scheduled"
              [attr.x]="row.scheduled.x"
              [attr.width]="row.scheduled.width"
              [attr.y]="SCHED_Y"
              [attr.height]="BAR_H"
              rx="3"
              [attr.fill]="row.color"
            />
            <text class="label" [attr.x]="row.scheduled.x" [attr.y]="SCHED_LABEL_Y[i % 2]">
              {{ row.label }}
            </text>
            <!-- From the scheduled start to the actual one: a slant is lateness. -->
            @if (row.connector; as c) {
              <line
                class="connector"
                [attr.x1]="c.from"
                [attr.y1]="SCHED_Y + BAR_H"
                [attr.x2]="c.to"
                [attr.y2]="row.y"
              />
            }
            <!-- Actual: a bar per bus -->
            @for (r of row.runs; track $index) {
              <rect
                class="actual"
                [attr.x]="r.x"
                [attr.width]="r.width"
                [attr.y]="row.y"
                [attr.height]="BAR_H"
                rx="3"
                [attr.fill]="row.color"
                [attr.stroke]="row.color"
                [class.pending]="row.trip.kind === 'pending'"
              />
            }
            @for (m of row.missing; track $index) {
              <rect
                class="missing"
                [attr.x]="m.x"
                [attr.width]="m.width"
                [attr.y]="row.y"
                [attr.height]="BAR_H"
                [attr.fill]="m.why === 'not-run' ? 'url(#not-run)' : 'url(#not-seen)'"
                [attr.stroke]="m.why === 'not-run' ? notRun : notSeen"
              />
            }
            @if (row.noData; as n) {
              <rect
                class="no-data"
                [attr.x]="n.x"
                [attr.width]="n.width"
                [attr.y]="row.y"
                [attr.height]="BAR_H"
                rx="3"
              />
            }
            @for (c of row.changes; track $index) {
              <g class="marker" [attr.transform]="'translate(' + c + ',' + (row.y - 1) + ')'">
                <circle r="7" [attr.fill]="change" />
                <text class="marker-text" y="3.5">⇄</text>
              </g>
            }
            @if (row.lostAt !== null) {
              <g
                class="marker"
                [attr.transform]="'translate(' + row.lostAt + ',' + (row.y + BAR_H / 2) + ')'"
              >
                <circle r="7" [attr.fill]="notRun" />
                <text class="marker-text" y="3.5">✕</text>
              </g>
            }
            @if (row.detourAt !== null) {
              <g
                class="marker"
                [attr.transform]="'translate(' + row.detourAt + ',' + (row.y - 1) + ')'"
              >
                <circle r="7" [attr.fill]="change" />
                <text class="marker-text" y="3.5">↷</text>
              </g>
            }
            <text class="label bus" [attr.x]="row.busX" [attr.y]="BUS_LABEL_Y[i % 2]">
              {{ row.busLabel }}
            </text>
          </g>
        }
      </svg>
    </div>
    @if (tip(); as t) {
      <div class="tooltip" role="status" [style.left.px]="t.left">
        <strong>{{ t.title }}</strong>
        <span>Scheduled {{ t.scheduled }}</span>
        <span>Ran {{ t.actual }}{{ t.buses ? ' · bus ' + t.buses : '' }}</span>
        <span>Set off {{ t.start }} · last seen {{ t.end }}</span>
        <span class="kind">{{ t.kind }}</span>
        @if (t.note) {
          <span class="note">{{ t.note }}</span>
        }
      </div>
    }
  `,
  styles: `
    :host {
      display: block;
      position: relative;
    }

    .scroll {
      overflow-x: auto;
    }

    svg {
      display: block;
      font-size: 11px;
    }

    .grid {
      stroke: var(--border);
      stroke-width: 1;
    }

    .tick {
      fill: var(--muted);
      text-anchor: middle;
    }

    .lane {
      fill: var(--muted);
      font-weight: 600;
    }

    .label {
      fill: var(--fg);
      pointer-events: none;
    }

    .label.bus {
      fill: var(--muted);
    }

    .hit {
      fill: transparent;
      cursor: pointer;
    }

    .actual {
      fill-opacity: 0.4;
      stroke-width: 1.5;
    }

    .actual.pending {
      stroke-dasharray: 3 3;
      fill-opacity: 0.15;
    }

    .missing {
      stroke-width: 1;
    }

    .no-data {
      fill: none;
      stroke: var(--muted);
      stroke-dasharray: 4 3;
    }

    .connector {
      stroke: var(--muted);
      stroke-width: 1;
      stroke-dasharray: 2 2;
    }

    .marker {
      pointer-events: none;
    }

    .marker-text {
      fill: #fff;
      font-size: 10px;
      font-weight: 700;
      text-anchor: middle;
    }

    .trip.dim {
      opacity: 0.35;
    }

    .trip.chosen .scheduled,
    .trip.chosen .actual {
      stroke: var(--fg);
      stroke-width: 2;
    }

    .tooltip {
      position: absolute;
      top: ${CHART_HEIGHT + 4}px;
      z-index: 2;
      display: flex;
      flex-direction: column;
      gap: 0.1rem;
      max-width: 22rem;
      padding: 0.5rem 0.7rem;
      font-size: 0.82rem;
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 6px;
      box-shadow: 0 4px 14px rgba(0, 0, 0, 0.12);
      pointer-events: none;
    }

    .tooltip .kind {
      font-weight: 600;
    }

    .tooltip .note {
      color: var(--muted);
    }
  `,
})
export class BrigadeDayChart {
  readonly trips = input.required<BrigadeTrip[]>();
  /** The chosen trip's index, or null. */
  readonly selected = input<number | null>(null);
  readonly choose = output<number>();

  protected readonly hovered = signal<number | null>(null);
  protected readonly height = CHART_HEIGHT;
  protected readonly AXIS_Y = AXIS_Y;
  protected readonly SCHED_LABEL_Y = SCHED_LABEL_Y;
  protected readonly SCHED_Y = SCHED_Y;
  protected readonly ACTUAL_Y = ACTUAL_Y;
  protected readonly BUS_LABEL_Y = BUS_LABEL_Y;
  protected readonly BAR_H = BAR_H;
  protected readonly notRun = NOT_RUN_COLOR;
  protected readonly notSeen = NOT_SEEN_COLOR;
  protected readonly change = CHANGE_COLOR;

  private readonly available = hostWidth(900, 320);

  /** The time span drawn: a quarter of an hour either side of everything scheduled or run. */
  private readonly range = computed(() => {
    const times = this.trips().flatMap((t) => [
      Date.parse(t.departure),
      Date.parse(t.arrival),
      ...t.runs.flatMap((r) => [Date.parse(r.departure), Date.parse(r.arrival)]),
    ]);
    if (!times.length) return { from: 0, to: 60 * MINUTE };
    return { from: Math.min(...times) - 15 * MINUTE, to: Math.max(...times) + 15 * MINUTE };
  });

  /** px per minute: fit the width, but never tighter than {@link MIN_PX_PER_MIN}. */
  private readonly ppm = computed(() => {
    const { from, to } = this.range();
    const minutes = (to - from) / MINUTE;
    return Math.max(MIN_PX_PER_MIN, (this.available() - LEFT - RIGHT) / minutes);
  });

  protected readonly width = computed(() => {
    const { from, to } = this.range();
    return Math.round(LEFT + RIGHT + ((to - from) / MINUTE) * this.ppm());
  });

  private x(iso: string | number): number {
    const t = typeof iso === 'number' ? iso : Date.parse(iso);
    return LEFT + ((t - this.range().from) / MINUTE) * this.ppm();
  }

  private bar(from: string, to: string): Bar {
    const x = this.x(from);
    return { x, width: Math.max(2, this.x(to) - x) };
  }

  /** Whole hours (local, which are whole UTC hours in Poland) inside the span. */
  protected readonly ticks = computed(() => {
    const { from, to } = this.range();
    const hour = 60 * MINUTE;
    const step = this.ppm() * 60 < 48 ? 2 * hour : hour;
    const ticks: { x: number; label: string }[] = [];
    for (let t = Math.ceil(from / hour) * hour; t <= to; t += step) {
      ticks.push({ x: this.x(t), label: localTime(new Date(t).toISOString()) });
    }
    return ticks;
  });

  protected readonly rows = computed(() => {
    const trips = this.trips();
    return trips.map((t, i) => {
      const scheduled = this.bar(t.departure, t.arrival);
      // Labels of a row may run up to the start of the next trip in the same label row.
      const nextInRow = trips[i + 2];
      const room = (nextInRow ? this.x(nextInRow.departure) : this.width()) - scheduled.x - 4;
      const runs = t.runs.map((r) => this.bar(r.departure, r.arrival));
      const missing = t.missing.map((m) => ({ ...this.bar(m.from, m.to), why: m.why }));
      const first = t.runs[0];
      const last = t.runs[t.runs.length - 1];
      const busX = first ? this.x(first.departure) : scheduled.x;
      const nextBus = nextInRow?.runs[0] ? this.x(nextInRow.runs[0].departure) : this.width();
      const busLabel = first
        ? `${t.runs.map((r) => r.bus).join(' → ')} ${delayText(first.startDelay)}`
        : t.kind === 'pending'
          ? 'upcoming'
          : t.kind === 'not-recorded'
            ? 'no data'
            : '';
      const allX = [
        scheduled.x,
        scheduled.x + scheduled.width,
        ...runs.flatMap((r) => [r.x, r.x + r.width]),
      ];
      return {
        trip: t,
        y: ACTUAL_Y[i % 2],
        color: TRIP_COLORS[i % 2],
        scheduled,
        label: fit(`${localTime(t.departure)} ${t.line} → ${shortStop(t.headsign ?? t.to)}`, room),
        runs,
        missing,
        noData:
          !t.runs.length && !t.missing.length && (t.kind === 'not-recorded' || t.kind === 'pending')
            ? scheduled
            : null,
        connector: first ? { from: scheduled.x, to: this.x(first.departure) } : null,
        changes: t.runs.slice(1).map((r) => this.x(r.departure)),
        lostAt: t.kind === 'lost' && last ? this.x(last.arrival) : null,
        detourAt:
          t.kind === 'detour' && first
            ? (this.x(first.departure) + this.x(last.arrival)) / 2
            : null,
        busX,
        busLabel: fit(busLabel, nextBus - busX - 4),
        hit: { x: Math.min(...allX) - 2, width: Math.max(...allX) - Math.min(...allX) + 4 },
      };
    });
  });

  protected readonly tip = computed(() => {
    const i = this.hovered();
    if (i === null) return null;
    const row = this.rows()[i];
    if (!row) return null;
    const t = row.trip;
    const first = t.runs[0];
    const last = t.runs[t.runs.length - 1];
    return {
      left: Math.max(0, Math.min(row.hit.x, this.width() - 360)),
      title: tripLabel(t),
      scheduled: `${span(t.departure, t.arrival)} (${t.from} → ${t.to})`,
      actual: actualSpan(t),
      buses: t.runs.length ? busesText(t) : '',
      start: delayText(first?.startDelay),
      end: delayText(last?.endDelay),
      kind: KIND_LABELS[t.kind],
      note: t.note,
    };
  });
}
