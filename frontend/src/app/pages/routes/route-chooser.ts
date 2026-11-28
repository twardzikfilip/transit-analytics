import { Component, input, output } from '@angular/core';
import { RouteRef } from '../../core/models/models';
import { activeLang } from '../../core/i18n/t';
import {
  CAPACITIES,
  RouteTable,
  capacityDescription,
  capacityLabel,
  geometryLabel,
} from './format';
import { t } from '../../core/i18n/t';

/**
 * The routes to pick from: a row per kind of route (urban, suburban, night, regional), a column per geometry; each route
 * a button whose border and fill tell its capacity class, the chosen one ringed.
 */
@Component({
  selector: 'app-route-chooser',
  templateUrl: './route-chooser.html',
  styleUrl: './route-chooser.scss',
  host: { role: 'region', 'aria-label': 'Lines' },
})
export class RouteChooser {
  protected readonly t = t;
  readonly table = input.required<RouteTable>();
  /** The chosen route's id, or null. */
  readonly selected = input<string | null>(null);
  /** Shown while there are no routes yet. */
  readonly message = input('');
  readonly choose = output<string>();

  protected readonly capacities = CAPACITIES;
  protected readonly capacityLabel = capacityLabel;
  protected readonly capacityDescription = capacityDescription;

  /** "Route 5: arcantial, main capacity". German keeps its nouns' capitals. */
  protected chipTitle(l: RouteRef): string {
    const lower = (s: string) => (activeLang() === 'de' ? s : s.toLowerCase());
    return (
      t('routes.chooser.title', { route: l.name, geometry: lower(geometryLabel(l.geometry)) }) +
      (l.capacityClass
        ? t('routes.chooser.capacity', { capacity: lower(capacityLabel(l.capacityClass)) })
        : '')
    );
  }
  protected readonly geometryLabel = geometryLabel;
}
