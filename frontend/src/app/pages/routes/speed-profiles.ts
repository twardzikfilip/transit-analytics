import { Component, input } from '@angular/core';
import { SegmentProfile } from '../../core/models/models';
import { SegmentChart } from './segment-chart';
import { LocalNumberPipe } from '../../shared/i18n/locale-pipes';
import { t, tn } from '../../core/i18n/t';

/** Peak-hour speed on each stretch between stops, a chart per direction, each also as a table. */
@Component({
  selector: 'app-speed-profiles',
  imports: [LocalNumberPipe, SegmentChart],
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
  protected readonly t = t;
  readonly segments = input.required<SegmentProfile[]>();
  readonly loading = input(false);
}
