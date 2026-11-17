import { Component, computed, input } from '@angular/core';
import { DatePipe } from '@angular/common';
import { DelayExample } from '../../core/models/models';
import { hostWidth } from '../../shared/host-width';
import { localTime } from '../../shared/format/time';
import { niceTicks } from '../../shared/time-series-chart/chart-geometry';
import { changeClass, delayText, signedChange } from './delay-scale';

const HEIGHT = 230;
const MARGIN = { top: 12, right: 16, bottom: 12, left: 52 };

/** The scheduled time of leaving a stop: when the bus left, minus its delay then. */
export function scheduledTime(left: string, delaySeconds: number): string {
  return localTime(new Date(Date.parse(left) - delaySeconds * 1000).toISOString());
}

/**
 * One run's delay on leaving each stop: a line chart (stops evenly along the x axis, delay in minutes up), the
 * chosen stretch drawn bold, and a table with the times and the change at each stop.
 */
@Component({
  selector: 'app-trip-profile',
  imports: [DatePipe],
  template: `
    <h3>
      {{ run().day | date: 'EEE d MMM' }} · brigade {{ run().brigade ?? '—' }} · bus
      {{ run().vehicle }} · towards {{ run().headsign ?? '—' }}
    </h3>
    <p class="muted">
      Left {{ run().stops[0].stop }} at {{ time(run().stops[0].left) }}
      {{ delayText(run().stops[0].delaySeconds) }}; {{ signed(run().gainSeconds) }} min by
      {{ run().stops[run().stops.length - 1].stop }}.
    </p>
    <svg
      [attr.width]="width()"
      [attr.height]="height"
      role="img"
      [attr.aria-label]="
        'Delay on leaving each stop, from ' +
        delayText(run().stops[0].delaySeconds) +
        ' to ' +
        delayText(run().stops[run().stops.length - 1].delaySeconds)
      "
    >
      @for (t of ticks(); track t) {
        <line
          class="grid"
          [class.zero]="t === 0"
          [attr.x1]="margin.left"
          [attr.x2]="width() - margin.right"
          [attr.y1]="y(t * 60)"
          [attr.y2]="y(t * 60)"
        />
        <text class="tick" [attr.x]="margin.left - 6" [attr.y]="y(t * 60) + 4">
          {{ t > 0 ? '+' : '' }}{{ t }} min
        </text>
      }
      <path class="line" [attr.d]="path()" />
      @for (s of highlighted(); track $index) {
        <line
          class="chosen"
          [attr.x1]="x(s)"
          [attr.x2]="x(s + 1)"
          [attr.y1]="y(run().stops[s].delaySeconds)"
          [attr.y2]="y(run().stops[s + 1].delaySeconds)"
        />
      }
      @for (p of run().stops; track $index; let i = $index) {
        <circle class="dot" [attr.cx]="x(i)" [attr.cy]="y(p.delaySeconds)" r="3.5">
          <title>{{ p.stop }}: {{ delayText(p.delaySeconds) }}</title>
        </circle>
      }
    </svg>
    <div class="data-table-wrap">
      <table class="data-table">
        <thead>
          <tr>
            <th>Stop left</th>
            <th class="num">At</th>
            <th class="num">Scheduled</th>
            <th class="num">Delay</th>
            <th class="num">Change</th>
          </tr>
        </thead>
        <tbody>
          @for (p of run().stops; track $index; let i = $index) {
            <tr [class.chosen-row]="highlighted().includes(i - 1)">
              <td>{{ p.stop }}</td>
              <td class="num">{{ time(p.left) }}</td>
              <td class="num muted">{{ scheduled(p.left, p.delaySeconds) }}</td>
              <td class="num">{{ delayText(p.delaySeconds) }}</td>
              <td class="num">
                @if (i > 0 && run().stops[i - 1].seq + 1 === p.seq) {
                  <span
                    class="chip"
                    [style.background]="color(p.delaySeconds - run().stops[i - 1].delaySeconds)"
                    [class.light-text]="dark(p.delaySeconds - run().stops[i - 1].delaySeconds)"
                    >{{ signed(p.delaySeconds - run().stops[i - 1].delaySeconds) }}</span
                  >
                } @else if (i > 0) {
                  <span class="muted" title="A stop between them wasn't seen being passed">—</span>
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
    </div>
  `,
  styles: `
    :host {
      display: block;
    }

    h3 {
      margin: 0 0 0.2rem;
      font-size: 1rem;
    }

    p.muted {
      margin: 0 0 0.5rem;
      font-size: 0.9rem;
    }

    svg {
      display: block;
      max-width: 100%;
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
      font-size: 0.75rem;
      text-anchor: end;
    }

    .line {
      fill: none;
      stroke: var(--chart-line);
      stroke-width: 2;
    }

    .chosen {
      stroke: #a32626;
      stroke-width: 4;
      stroke-linecap: round;
    }

    .dot {
      fill: var(--chart-line);
      stroke: var(--surface);
      stroke-width: 1.5;
    }

    .chip {
      display: inline-block;
      min-width: 3.4rem;
      padding: 0.05rem 0.35rem;
      border-radius: 4px;
      text-align: right;
      color: #1f2933;
      font-variant-numeric: tabular-nums;
    }

    .light-text {
      color: #fff;
    }

    tr.chosen-row td {
      background: var(--accent-soft);
    }
  `,
})
export class TripProfile {
  readonly run = input.required<DelayExample>();
  /** The stretch to draw bold, by stop names. */
  readonly stretch = input<{ from: string; to: string } | null>(null);

  protected readonly width = hostWidth(700, 280);
  protected readonly height = HEIGHT;
  protected readonly margin = MARGIN;

  /** Whole minutes from the lowest to the highest delay (and 0). */
  protected readonly ticks = computed(() => {
    const minutes = this.run().stops.map((s) => s.delaySeconds / 60);
    return niceTicks(Math.max(0, ...minutes), 5, Math.min(0, ...minutes));
  });

  /** Indexes i of the chosen stretch (stop i to stop i + 1) in this run. */
  protected readonly highlighted = computed(() => {
    const s = this.stretch();
    const stops = this.run().stops;
    if (!s) return [];
    const found: number[] = [];
    for (let i = 0; i + 1 < stops.length; i++) {
      if (stops[i].stop === s.from && stops[i + 1].stop === s.to) found.push(i);
    }
    return found;
  });

  protected readonly path = computed(() =>
    this.run()
      .stops.map(
        (p, i) => `${i ? 'L' : 'M'}${this.x(i).toFixed(1)},${this.y(p.delaySeconds).toFixed(1)}`,
      )
      .join(''),
  );

  protected x(i: number): number {
    const n = Math.max(1, this.run().stops.length - 1);
    return MARGIN.left + (i / n) * (this.width() - MARGIN.left - MARGIN.right);
  }

  protected y(seconds: number): number {
    const t = this.ticks();
    const lo = t[0] * 60;
    const hi = t[t.length - 1] * 60;
    return (
      HEIGHT -
      MARGIN.bottom -
      ((seconds - lo) / (hi - lo || 1)) * (HEIGHT - MARGIN.top - MARGIN.bottom)
    );
  }

  protected readonly time = localTime;
  protected readonly scheduled = scheduledTime;
  protected readonly signed = signedChange;
  protected readonly delayText = delayText;

  protected color(seconds: number): string {
    return changeClass(seconds).color;
  }

  protected dark(seconds: number): boolean {
    return changeClass(seconds).dark;
  }
}
