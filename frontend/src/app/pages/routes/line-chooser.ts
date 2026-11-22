import { Component, input, output } from '@angular/core';
import { LineRef } from '../../core/models/models';
import { activeLang } from '../../core/i18n/t';
import { CAPACITIES, LineTable, capacityDescription, capacityLabel, geometryLabel } from './format';
import { t, tn } from '../../core/i18n/t';

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
  protected readonly t = t;
  readonly table = input.required<LineTable>();
  /** The chosen line's id, or null. */
  readonly selected = input<string | null>(null);
  /** Shown while there are no lines yet. */
  readonly message = input('');
  readonly choose = output<string>();

  protected readonly capacities = CAPACITIES;
  protected readonly capacityLabel = capacityLabel;
  protected readonly capacityDescription = capacityDescription;

  /** "Line 5: arcantial, main capacity". German keeps its nouns' capitals. */
  protected chipTitle(l: LineRef): string {
    const lower = (s: string) => (activeLang() === 'de' ? s : s.toLowerCase());
    return (
      t('routes.chooser.title', { line: l.name, geometry: lower(geometryLabel(l.geometry)) }) +
      (l.capacityClass
        ? t('routes.chooser.capacity', { capacity: lower(capacityLabel(l.capacityClass)) })
        : '')
    );
  }
  protected readonly geometryLabel = geometryLabel;
}
