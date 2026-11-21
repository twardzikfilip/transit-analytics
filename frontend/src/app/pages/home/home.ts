import { Component } from '@angular/core';
import { LiveMap } from './live-map/live-map';
import { PositionHistory } from './position-history/position-history';

/**
 * Home: the live map (vehicles now, with their delays) and, below it, the recorded positions over a chosen time
 * window. The time window, line and operator filters belong to the recorded positions only.
 */
@Component({
  selector: 'app-home',
  imports: [LiveMap, PositionHistory],
  template: `
    <section class="card live" aria-label="Live map">
      <app-live-map />
    </section>
    <section class="history" aria-label="Recorded positions">
      <app-position-history />
    </section>
  `,
  styles: `
    .live {
      margin-bottom: 1.5rem;
    }
  `,
})
export class Home {}
