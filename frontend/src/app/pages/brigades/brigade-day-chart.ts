import { Component, computed, input, output, signal } from '@angular/core';
import { BrigadeTrip } from '../../core/models/models';
import { hostWidth } from '../../shared/host-width';
import {
  CHANGE_COLOR,
  kindLabel,
  NOT_RUN_COLOR,
  NOT_SEEN_COLOR,
  actualSpan,
  busesText,
  departureDelayText,
  span,
  tripLabel,
} from './brigade-format';
import {
  ACTUAL_Y,
  AXIS_Y,
  BAR_H,
  BUS_LABEL_Y,
  CHART_HEIGHT,
  SCHED_LABEL_Y,
  SCHED_Y,
  hourTicks,
  timeScale,
  tripRows,
} from './brigade-day-layout';
import { t as tr } from '../../core/i18n/t';
import { phrasesText } from '../../shared/i18n/phrases';

export { CHART_HEIGHT, MIN_PX_PER_MIN, fit } from './brigade-day-layout';

/**
 * A brigade's day on one time line: each scheduled trip as a bar (consecutive trips alternating in colour, labelled
 * with line and destination), and under it what actually ran, in the same colour, a bar per bus (labelled with the
 * bus and how late it set off). Parts nobody ran are hatched red, parts no bus was seen on grey; trips without data
 * are outlined. A thin line joins each scheduled start to the actual one, so lateness shows as a slant; markers flag
 * a change of bus, a lost trip and a detour. Hover a trip for its details; click to choose it.
 */
@Component({
  selector: 'app-brigade-day-chart',
  templateUrl: './brigade-day-chart.html',
  styleUrl: './brigade-day-chart.scss',
  // The tooltip sits just under the chart.
  host: { '[style.--chart-height.px]': 'height' },
})
export class BrigadeDayChart {
  /** Translates (`t` is the tooltip in the template). */
  protected readonly tr = tr;
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
  private readonly scale = computed(() => timeScale(this.trips(), this.available()));
  protected readonly width = computed(() => this.scale().width);
  protected readonly ticks = computed(() => hourTicks(this.scale()));
  protected readonly rows = computed(() => tripRows(this.trips(), this.scale()));

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
      start: departureDelayText(first?.startDelay),
      end: departureDelayText(last?.endDelay),
      kind: kindLabel(t.kind),
      note: phrasesText(t.note),
    };
  });
}
