import { Component, input } from '@angular/core';
import { DepotRun } from '../../core/models/models';
import { clock } from '../../shared/format/time';

/** The line's depot runs (pull-outs and pull-ins) with their brigades, in yellow with a label. */
@Component({
  selector: 'app-depot-runs',
  templateUrl: './depot-runs.html',
  styleUrl: './depot-runs.scss',
})
export class DepotRuns {
  readonly depotRuns = input.required<DepotRun[]>();
  protected readonly clock = clock;
}
