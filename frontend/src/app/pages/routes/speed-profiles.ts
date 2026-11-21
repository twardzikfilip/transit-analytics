import { DecimalPipe } from '@angular/common';
import { Component, input } from '@angular/core';
import { SegmentProfile } from '../../core/models/models';
import { SegmentChart } from './segment-chart';

/** Peak-hour speed on each stretch between stops, a chart per direction, each also as a table. */
@Component({
  selector: 'app-speed-profiles',
  imports: [DecimalPipe, SegmentChart],
  templateUrl: './speed-profiles.html',
  styles: `
    :host {
      display: block;
    }
    details {
      margin-top: 0.5rem;
      font-size: 0.85rem;
    }
    summary {
      color: var(--muted);
      cursor: pointer;
    }
    details table {
      margin-top: 0.5rem;
    }
    .data-table.compact {
      font-size: 0.85rem;
    }
    .data-table.compact th,
    .data-table.compact td {
      padding: 0.3rem 0.6rem;
    }
  `,
})
export class SpeedProfiles {
  readonly segments = input.required<SegmentProfile[]>();
  readonly loading = input(false);
}
