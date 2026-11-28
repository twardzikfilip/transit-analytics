import { Component } from '@angular/core';
import { FaultyTrackers } from './faulty-trackers';
import { LiveMap } from './live-map/live-map';
import { PositionHistory } from './position-history/position-history';
import { TimetableWarning } from './timetable-warning';
import { t } from '../../core/i18n/t';

/**
 * Home: timetables about to end, buses whose tracker probably fails (to check regularly), the live map (vehicles now, with their delays) and,
 * below it, the recorded positions over a chosen time window. The time window, route and operator filters belong to the recorded positions only.
 */
@Component({
  selector: 'app-home',
  imports: [TimetableWarning, FaultyTrackers, LiveMap, PositionHistory],
  template: `
    <app-timetable-warning />
    <app-faulty-trackers />
    <section class="card live" [attr.aria-label]="t('live.title')">
      <app-live-map />
    </section>
    <section class="history" [attr.aria-label]="t('history.title')">
      <app-position-history />
    </section>
  `,
  styles: `
    .live {
      margin-bottom: 1.5rem;
    }
  `,
})
export class Home {
  protected readonly t = t;
}
