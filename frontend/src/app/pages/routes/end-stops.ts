import { Component, input } from '@angular/core';
import { EndStop } from '../../core/models/models';
import { t } from '../../core/i18n/t';

/**
 * The route's standard end stops (where buses turn round in service), and the other stops depot runs use. Stops
 * closer than 500 m show as one, named where runs end; the others are listed after it.
 */
@Component({
  selector: 'app-end-stops',
  templateUrl: './end-stops.html',
  styleUrl: './end-stops.scss',
})
export class EndStops {
  protected readonly t = t;
  readonly endStops = input.required<EndStop[]>();
  readonly depotStops = input.required<EndStop[]>();
  /** False when the timetable has no brigades, so depot runs can't be told apart. */
  readonly brigadesKnown = input(true);
}
