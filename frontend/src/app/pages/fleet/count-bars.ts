import { Component, computed, input, signal } from '@angular/core';
import { ModelCount } from '../../core/models/models';

/**
 * Buses per kind (model, size class or make): a bar per kind, the fleet's buses in Bootstrap's gray-500 and those in use over them
 * in the primary colour, both fully rounded, with "in use/fleet" beside. Long lists show their first {@link CountBars.first} with a button for all.
 */
@Component({
  selector: 'app-count-bars',
  template: `
    <ul class="model-bars">
      @for (m of shown(); track m.model) {
        <li [title]="label()(m.model) + ': ' + m.inUse + ' of ' + m.fleet + ' in use'">
          <span class="name">{{ label()(m.model) }}</span>
          <span class="track">
            <span class="fleet" [style.width.%]="(m.fleet / max()) * 100"></span>
            <span class="used" [style.width.%]="(m.inUse / max()) * 100"></span>
          </span>
          <span class="num">{{ m.inUse }}/{{ m.fleet }}</span>
        </li>
      }
    </ul>
    @if (counts().length > first()) {
      <button type="button" class="more" (click)="all.set(!all())">
        {{ all() ? 'Show fewer' : 'Show all ' + counts().length }}
      </button>
    }
  `,
  styles: `
    .model-bars {
      margin: 0.5rem 0 0;
      padding: 0;
      list-style: none;
      display: flex;
      flex-direction: column;
      gap: 0.35rem;
      font-size: 0.85rem;

      /* Name and count on one line, the bar across the tile below them. */
      li {
        display: grid;
        grid-template-columns: minmax(0, 1fr) auto;
        align-items: center;
        gap: 0.15rem 0.5rem;
      }

      .name {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .track {
        grid-column: 1 / -1;
        grid-row: 2;
        position: relative;
        height: 10px;
      }

      .fleet,
      .used {
        position: absolute;
        left: 0;
        top: 0;
        height: 100%;
        border-radius: 999px;
      }

      .fleet {
        background: var(--progress-rest);
      }

      .used {
        background: var(--primary);
      }

      .num {
        font-variant-numeric: tabular-nums;
        text-align: right;
      }
    }

    .more {
      margin-top: 0.5rem;
      padding: 0.2rem 0.6rem;
      font-size: 0.85rem;
    }
  `,
})
export class CountBars {
  readonly counts = input.required<ModelCount[]>();
  /** How a kind is named; as given by default. */
  readonly label = input<(kind: string) => string>((kind) => kind);
  /** Shown before "Show all". */
  readonly first = input(8);

  protected readonly all = signal(false);
  protected readonly max = computed(() => Math.max(1, ...this.counts().map((m) => m.fleet)));
  protected readonly shown = computed(() =>
    this.all() ? this.counts() : this.counts().slice(0, this.first()),
  );
}
