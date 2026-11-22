import { Component, ElementRef, computed, inject, input, output, signal } from '@angular/core';
import { t } from '../../core/i18n/t';
/** An operator to choose, with how many vehicles it has. */
export interface AgencyOption {
  name: string;
  vehicles: number;
}

/** "All operators", "BKM" or "2 of 3 operators": the picker's button text. */
export function pickerLabel(options: AgencyOption[], selected: string[]): string {
  if (!selected.length || options.every((o) => selected.includes(o.name)))
    return t('time.allOperators');
  return selected.length === 1
    ? selected[0]
    : t('time.someOperators', { count: selected.length, total: options.length });
}

/**
 * The selection after ticking or unticking an operator. Empty means all; ticking the last one back gives empty
 * again, so the URL stays clean when everything is chosen.
 */
export function toggleAgency(options: AgencyOption[], selected: string[], name: string): string[] {
  const current = selected.length ? selected : options.map((o) => o.name);
  const next = current.includes(name) ? current.filter((n) => n !== name) : [...current, name];
  const all = options.every((o) => next.includes(o.name));
  return all ? [] : options.map((o) => o.name).filter((n) => next.includes(n));
}

/**
 * A dropdown of checkboxes, one per operator, choosing whose vehicles the page includes. At least one stays
 * chosen. Closes on Escape or a click outside.
 */
@Component({
  selector: 'app-agency-picker',
  template: `
    <button
      type="button"
      class="toggle"
      [class.active]="selected().length > 0"
      aria-haspopup="true"
      [attr.aria-expanded]="open()"
      (click)="open.set(!open())"
    >
      {{ label() }} <span aria-hidden="true">▾</span>
    </button>
    @if (open()) {
      <fieldset class="panel">
        <legend class="sr-only">{{ t('time.operatorsToInclude') }}</legend>
        @for (o of options(); track o.name) {
          <label class="checkbox">
            <input
              type="checkbox"
              [checked]="isChosen(o.name)"
              [disabled]="isChosen(o.name) && chosenCount() === 1"
              (change)="toggle(o.name)"
            />
            {{ o.name }} <span class="muted">{{ o.vehicles }}</span>
          </label>
        } @empty {
          <span class="muted">{{ t('time.noOperators') }}</span>
        }
        @if (selected().length) {
          <button type="button" class="all" (click)="changed.emit([])">
            {{ t('time.selectAll') }}
          </button>
        }
      </fieldset>
    }
  `,
  styles: `
    :host {
      position: relative;
      display: inline-block;
    }

    .panel {
      position: absolute;
      z-index: 10;
      top: calc(100% + 4px);
      left: 0;
      min-width: 220px;
      margin: 0;
      padding: 0.5rem 0.75rem;
      display: flex;
      flex-direction: column;
      gap: 0.4rem;
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: var(--radius);
      box-shadow: var(--shadow-float);
    }

    label.checkbox {
      gap: 0.5rem;
      overflow-wrap: anywhere;
      cursor: pointer;
    }

    label .muted {
      margin-left: auto;
      font-variant-numeric: tabular-nums;
    }

    .all {
      align-self: start;
      padding: 0.2rem 0.6rem;
      font-size: 0.85rem;
    }

    /* On narrow screens the page column is too narrow for a floating panel: it opens in place, below the button. */
    @media (max-width: 700px) {
      .panel {
        position: static;
        min-width: 0;
        margin-top: 4px;
      }
    }
  `,
  host: {
    '(document:click)': 'closeOutside($event)',
    '(keydown.escape)': 'open.set(false)',
  },
})
export class AgencyPicker {
  protected readonly t = t;
  private readonly host = inject(ElementRef<HTMLElement>);

  /** Operators to choose from, with their vehicle counts. */
  readonly options = input.required<AgencyOption[]>();
  /** The chosen operators; empty for all. */
  readonly selected = input.required<string[]>();
  readonly changed = output<string[]>();

  protected readonly open = signal(false);
  protected readonly label = computed(() => pickerLabel(this.options(), this.selected()));
  protected readonly chosenCount = computed(
    () => this.options().filter((o) => this.isChosen(o.name)).length,
  );

  protected isChosen(name: string): boolean {
    return !this.selected().length || this.selected().includes(name);
  }

  protected toggle(name: string): void {
    this.changed.emit(toggleAgency(this.options(), this.selected(), name));
  }

  protected closeOutside(event: Event): void {
    if (!this.host.nativeElement.contains(event.target as Node)) this.open.set(false);
  }
}
