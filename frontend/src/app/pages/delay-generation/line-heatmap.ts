import { Component, computed, input, output } from '@angular/core';
import { DelayLineStretch } from '../../core/models/models';
import { changeClass, delayText, signedChange } from './delay-scale';
import { percent } from '../../shared/format/number';
import { t, tn } from '../../core/i18n/t';

/** A heatmap cell: whole seconds with a sign, "+31", "−6", "0" (never "−0"). */
export function cellText(seconds: number): string {
  const r = Math.round(seconds);
  return r === 0 ? '0' : r > 0 ? `+${r}` : `−${-r}`;
}

/** The hours (0–23) with a figure on any stretch, so empty night hours take no room. */
export function activeHours(stretches: DelayLineStretch[]): number[] {
  const hours: number[] = [];
  for (let h = 0; h < 24; h++) {
    if (stretches.some((s) => s.byHour[h] !== null)) hours.push(h);
  }
  return hours;
}

/**
 * One direction of a line: its stretches in route order (rows) with their figures, and the average change in delay
 * by hour of the day (columns, coloured on the diverging scale with the number in each cell). Click a row to see the
 * runs that gained the most there.
 */
@Component({
  selector: 'app-line-heatmap',
  template: `
    <div class="data-table-wrap">
      <table class="data-table heatmap">
        <thead>
          <tr>
            <th>{{ t('delayGen.col.stretch') }}</th>
            <th class="num" [title]="t('delayGen.heat.averageTitle')">
              {{ t('delayGen.heat.average') }}
            </th>
            <th class="num" [title]="t('delayGen.heat.p90Title')">{{ t('delayGen.heat.p90') }}</th>
            <th class="num" [title]="t('delayGen.heat.over1Title')">
              {{ t('delayGen.heat.over1') }}
            </th>
            @for (h of hours(); track h) {
              <th class="hour" [title]="h + ':00–' + (h + 1) + ':00'">{{ h }}</th>
            }
          </tr>
        </thead>
        <tbody>
          @for (s of stretches(); track s.from + s.to; let i = $index) {
            <tr
              [class.selected]="selected() === i"
              [attr.aria-selected]="selected() === i"
              tabindex="0"
              (click)="choose.emit(i)"
              (keydown.enter)="choose.emit(i)"
            >
              <td class="stretch">
                {{ s.from }} <span class="muted">→</span> {{ s.to }}
                <div class="muted note">
                  {{
                    t('delayGen.heat.note', {
                      buses: s.runs,
                      leaving: delayText(s.delayBefore),
                      median: signed(s.medianSeconds),
                    })
                  }}
                </div>
              </td>
              <td class="num">
                <span
                  class="chip"
                  [style.background]="color(s.meanSeconds)"
                  [class.light-text]="darkCell(s.meanSeconds)"
                  >{{ signed(s.meanSeconds) }}</span
                >
              </td>
              <td class="num">{{ signed(s.p90Seconds) }}</td>
              <td class="num">{{ wholePercent(s.gainingShare) }}</td>
              @for (h of hours(); track h) {
                @if (s.byHour[h]; as v) {
                  <td
                    class="cell"
                    [style.background]="color(v)"
                    [class.light-text]="darkCell(v)"
                    [title]="h + ':00–' + (h + 1) + ':00: ' + signed(v) + ' per bus'"
                  >
                    {{ cellText(v) }}
                  </td>
                } @else if (s.byHour[h] === 0) {
                  <td class="cell" [style.background]="color(0)">0</td>
                } @else {
                  <td class="cell empty"></td>
                }
              }
            </tr>
          }
        </tbody>
      </table>
    </div>
  `,
  styles: `
    .heatmap tbody tr {
      cursor: pointer;

      &:hover td.stretch {
        background: var(--hover);
      }

      &.selected td.stretch {
        background: var(--accent-soft);
        box-shadow: inset 3px 0 0 var(--accent);
      }

      &:focus-visible {
        outline: 2px solid var(--accent);
        outline-offset: -2px;
      }
    }

    .stretch {
      min-width: 15rem;
    }

    .note {
      font-size: 0.8rem;
    }

    th.hour,
    td.cell {
      width: 2rem;
      padding: 0.35rem 0.1rem;
      text-align: center;
      font-size: 0.78rem;
      font-variant-numeric: tabular-nums;
      color: #1f2933;
    }

    td.cell {
      border-bottom: 1px solid var(--surface);
      border-right: 1px solid var(--surface);
    }

    td.cell.empty {
      background: transparent;
    }

    .chip {
      display: inline-block;
      min-width: 3.4rem;
      padding: 0.05rem 0.35rem;
      border-radius: 4px;
      text-align: right;
      color: #1f2933;
    }

    .light-text {
      color: #fff !important;
    }
  `,
})
export class LineHeatmap {
  protected readonly t = t;
  readonly stretches = input.required<DelayLineStretch[]>();
  /** Index of the chosen stretch, or null. */
  readonly selected = input<number | null>(null);
  readonly choose = output<number>();

  protected readonly hours = computed(() => activeHours(this.stretches()));
  protected readonly signed = signedChange;
  /** A share in whole percent, "42%". */
  protected readonly wholePercent = (share: number) => percent(share, { digits: 0 });
  protected readonly delayText = delayText;

  /** Whole seconds with a sign: "+31", "−6", "0" (never "−0"). */
  protected cellText(seconds: number): string {
    return cellText(seconds);
  }

  protected color(seconds: number): string {
    return changeClass(seconds).color;
  }

  protected darkCell(seconds: number): boolean {
    return changeClass(seconds).dark;
  }
}
