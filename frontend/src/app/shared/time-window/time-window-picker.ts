import { Component, computed, inject, input } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { toLocalInput } from '../format/time';
import { QueryChanges, injectSetQuery } from '../url-query';
import { AgencyOption, AgencyPicker } from './agency-picker';
import { WINDOW_PRESETS, customParams, presetParams, readAgencies, readWindow } from './time-window';

/**
 * The time window (and operators) of a report page, in the URL: from/to pickers in the first row; below, the
 * presets (1 min to 1 month, and all time) and, when the page passes operators, a dropdown to choose them. Whatever
 * the page projects (e.g. a line filter, a loading note) follows on the second row. Changing the window clears the
 * `reset` parameters (e.g. a page number).
 */
@Component({
  selector: 'app-time-window',
  imports: [AgencyPicker],
  template: `
    <div class="row">
      <label>
        From
        <input
          type="datetime-local"
          [value]="fromInput()"
          (change)="setCustom('from', $any($event.target).value)"
        />
      </label>
      <label>
        To
        <input
          type="datetime-local"
          [value]="toInput()"
          (change)="setCustom('to', $any($event.target).value)"
        />
      </label>
      @if (!window().from) {
        <span class="muted all">From the first recording</span>
      }
    </div>
    <div class="row">
      <div class="presets" role="group" aria-label="Time window">
        @for (p of presets; track p.key) {
          <button
            type="button"
            [class.active]="window().preset === p.key"
            [attr.aria-pressed]="window().preset === p.key"
            (click)="setPreset(p.key)"
          >
            {{ p.label }}
          </button>
        }
      </div>
      @if (agencies(); as options) {
        <app-agency-picker
          [options]="options"
          [selected]="selectedAgencies()"
          (changed)="setAgencies($event)"
        />
      }
      <ng-content />
    </div>
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
      margin-bottom: 1rem;
    }

    .row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.5rem 0.75rem;
    }

    label {
      flex-direction: row;
      align-items: center;
      gap: 0.4rem;
    }

    .presets {
      display: flex;
      flex-wrap: wrap;
      gap: 0.25rem;
    }

    .presets button {
      padding: 0.3rem 0.6rem;
      font-size: 0.85rem;
    }

    .all {
      font-size: 0.85rem;
    }
  `,
})
export class TimeWindowPicker {
  private readonly setQuery = injectSetQuery();
  private readonly params = toSignal(inject(ActivatedRoute).queryParamMap, { requireSync: true });

  /** The page's preset when the URL names none. */
  readonly defaultPreset = input('24h');
  /** Operators to choose from; the dropdown is shown only when given. */
  readonly agencies = input<AgencyOption[] | null>(null);
  /** Query parameters cleared whenever the window or operators change, e.g. a page number. */
  readonly reset = input<string[]>([]);

  protected readonly presets = WINDOW_PRESETS;
  protected readonly window = computed(() => readWindow(this.params(), this.defaultPreset()));
  protected readonly selectedAgencies = computed(() => readAgencies(this.params()));
  protected readonly fromInput = computed(() => {
    const from = this.window().from;
    return from ? toLocalInput(from) : '';
  });
  protected readonly toInput = computed(() => toLocalInput(this.window().to));

  protected setPreset(key: string): void {
    this.navigate(presetParams(key));
  }

  protected setCustom(which: 'from' | 'to', value: string): void {
    const params = customParams(this.window(), which, value);
    if (params) this.navigate(params);
  }

  protected setAgencies(agencies: string[]): void {
    this.navigate({ agency: agencies.length ? agencies : null });
  }

  private navigate(changes: QueryChanges): void {
    const cleared = Object.fromEntries(this.reset().map((k) => [k, null]));
    this.setQuery({ ...cleared, ...changes });
  }
}
