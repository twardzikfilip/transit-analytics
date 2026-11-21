import { Component, input, output } from '@angular/core';
import { CAPACITIES, LineTable, capacityLabel, geometryLabel } from './format';

/**
 * The lines to pick from: a row per kind of line (urban, suburban, night, regional), a column per geometry; each line
 * a button whose border and fill tell its capacity class, the chosen one ringed.
 */
@Component({
  selector: 'app-line-chooser',
  templateUrl: './line-chooser.html',
  styleUrl: './line-chooser.scss',
  host: { role: 'region', 'aria-label': 'Lines' },
})
export class LineChooser {
  readonly table = input.required<LineTable>();
  /** The chosen line's id, or null. */
  readonly selected = input<string | null>(null);
  /** Shown while there are no lines yet. */
  readonly message = input('');
  readonly choose = output<string>();

  protected readonly capacities = CAPACITIES;
  protected readonly capacityLabel = capacityLabel;
  protected readonly geometryLabel = geometryLabel;
}
