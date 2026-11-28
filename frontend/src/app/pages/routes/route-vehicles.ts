import { Component, input } from '@angular/core';
import { RouteVehicles } from '../../core/models/models';
import { spellsText } from './format';
import { LocalDatePipe } from '../../shared/i18n/locale-pipes';
import { t, tn } from '../../core/i18n/t';

/**
 * The buses that served a route: every bus with the days it ran and its brigades, the most used bus per brigade, and
 * the brigades that changed bus during a day.
 */
@Component({
  selector: 'app-route-vehicles',
  imports: [LocalDatePipe],
  templateUrl: './route-vehicles.html',
  styleUrl: './route-vehicles.scss',
})
export class RouteVehiclesSection {
  protected readonly t = t;
  protected readonly tn = tn;
  readonly vehicles = input<RouteVehicles | null>(null);
  readonly loading = input(false);
  protected readonly spellsText = spellsText;
}
