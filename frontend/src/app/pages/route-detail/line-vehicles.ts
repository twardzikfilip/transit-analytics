import { DatePipe } from '@angular/common';
import { Component, input } from '@angular/core';
import { LineVehicles } from '../../core/models/models';
import { spellsText } from './format';

/**
 * The buses that served a line: every bus with the days it ran and its brigades, the most used bus per brigade, and
 * the brigades that changed bus during a day.
 */
@Component({
  selector: 'app-line-vehicles',
  imports: [DatePipe],
  templateUrl: './line-vehicles.html',
  styleUrl: './line-vehicles.scss',
})
export class LineVehiclesSection {
  readonly vehicles = input<LineVehicles | null>(null);
  readonly loading = input(false);
  protected readonly spellsText = spellsText;
}
