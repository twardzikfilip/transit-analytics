import { Component, computed, input } from '@angular/core';
import { IncidentSpan, IncidentStep } from '../../core/models/models';
import { localTime } from '../format/time';
import { t } from '../../core/i18n/t';

/** How each kind of stretch is drawn and named in the legend. */
/** What a bus was doing, in the legend's order; `label` is a translation key. */
export const SPAN_KINDS: { kind: IncidentSpan['kind']; label: string }[] = [
  { kind: 'running', label: 'incident.running' },
  { kind: 'standing', label: 'incident.standing' },
  { kind: 'moving', label: 'incident.moving' },
  { kind: 'approach', label: 'incident.approach' },
  { kind: 'service', label: 'incident.service' },
];

/** Tick spacings to choose from, in minutes: the smallest giving at most six ticks wins. */
const TICK_MINUTES = [5, 10, 15, 30, 60, 120, 240];
/** Steps closer than this (percent of the plot) alternate between two levels. */
const STEP_SPACING = 3;
/** A moment (a zero-length stretch) is drawn this wide, in percent of the plot. */
const MOMENT_WIDTH = 0.8;

export interface TimelineLane {
  vehicle: string;
  bars: { kind: IncidentSpan['kind']; left: number; width: number; title: string }[];
}

/**
 * The time line of a change of bus: a lane per bus with what it did when, and the numbered steps of the story
 * on a time axis above (the list next to it tells them in words, so colour is never the only cue).
 */
@Component({
  selector: 'app-incident-timeline',
  template: `
    <ul class="legend" [attr.aria-label]="tr('incident.legend')">
      @for (k of kinds(); track k.kind) {
        <li><span class="swatch" [class]="k.kind"></span>{{ tr(k.label) }}</li>
      }
    </ul>
    <div class="plot" role="img" [attr.aria-label]="label()">
      <div class="row steps-row">
        <span class="lane-label muted">{{ tr('incident.steps') }}</span>
        <div class="track">
          @for (s of steps(); track $index) {
            <span
              class="step"
              [class.lift]="s.lift"
              [style.left.%]="s.left"
              [title]="s.time + ' · ' + s.text"
              >{{ $index + 1 }}</span
            >
          }
        </div>
      </div>
      @for (lane of lanes(); track lane.vehicle) {
        <div class="row">
          <span class="lane-label">{{ tr('incident.bus', { bus: lane.vehicle }) }}</span>
          <div class="track lane">
            @for (b of lane.bars; track $index) {
              <span
                class="bar"
                [class]="b.kind"
                [style.left.%]="b.left"
                [style.width.%]="b.width"
                [title]="b.title"
              ></span>
            }
          </div>
        </div>
      }
      <div class="row axis">
        <span class="lane-label"></span>
        <div class="track">
          @for (t of ticks(); track t.left) {
            <span class="tick" [style.left.%]="t.left">{{ t.label }}</span>
          }
        </div>
      </div>
    </div>
  `,
  styleUrl: './incident-timeline.scss',
})
export class IncidentTimeline {
  /** Translates (`t` is a loop variable in the template). */
  protected readonly tr = t;
  readonly spans = input.required<IncidentSpan[]>();
  readonly story = input.required<IncidentStep[]>();

  /** The time shown: every stretch and step, with a little room either side. */
  private readonly range = computed(() => {
    const times = [
      ...this.spans().flatMap((s) => [Date.parse(s.from), Date.parse(s.to)]),
      ...this.story().map((s) => Date.parse(s.at)),
    ];
    const min = Math.min(...times);
    const max = Math.max(...times);
    const pad = Math.max((max - min) * 0.03, 60_000);
    return { from: min - pad, to: max + pad };
  });

  private position(t: number): number {
    const { from, to } = this.range();
    return ((t - from) / (to - from)) * 100;
  }

  protected readonly kinds = computed(() => {
    const used = new Set(this.spans().map((s) => s.kind));
    return SPAN_KINDS.filter((k) => used.has(k.kind));
  });

  protected readonly lanes = computed<TimelineLane[]>(() => {
    const lanes = new Map<string, TimelineLane>();
    for (const s of this.spans()) {
      const lane = lanes.get(s.vehicle) ?? { vehicle: s.vehicle, bars: [] };
      lanes.set(s.vehicle, lane);
      const left = this.position(Date.parse(s.from));
      const width = this.position(Date.parse(s.to)) - left;
      const key = SPAN_KINDS.find((k) => k.kind === s.kind)?.label;
      const label = key ? t(key) : s.kind;
      const when =
        s.from === s.to ? `from ${localTime(s.from)}` : `${localTime(s.from)}–${localTime(s.to)}`;
      lane.bars.push({
        kind: s.kind,
        left,
        width: Math.max(width, MOMENT_WIDTH),
        title: t('incident.spanTitle', { bus: s.vehicle, what: label.toLowerCase(), when }),
      });
    }
    return [...lanes.values()];
  });

  /** Steps on the axis; one close after another sits a level up, so their numbers don't overlap. */
  protected readonly steps = computed(() => {
    let previous = -Infinity;
    let lifted = false;
    return this.story().map((s) => {
      const left = this.position(Date.parse(s.at));
      lifted = left - previous < STEP_SPACING && !lifted;
      previous = left;
      return { left, lift: lifted, time: localTime(s.at), text: s.text };
    });
  });

  protected readonly ticks = computed(() => {
    const { from, to } = this.range();
    const minutes = (to - from) / 60_000;
    const step = (TICK_MINUTES.find((m) => minutes / m <= 6) ?? 480) * 60_000;
    const ticks: { left: number; label: string }[] = [];
    for (let t = Math.ceil(from / step) * step; t <= to; t += step) {
      ticks.push({ left: this.position(t), label: localTime(new Date(t).toISOString()) });
    }
    return ticks;
  });

  protected readonly label = computed(() => {
    const { from, to } = this.range();
    const buses = this.lanes()
      .map((l) => l.vehicle)
      .join(' and ');
    return `Time line of buses ${buses}, ${localTime(new Date(from).toISOString())} to ${localTime(new Date(to).toISOString())}`;
  });
}
