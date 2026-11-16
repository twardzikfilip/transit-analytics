import { DecimalPipe } from '@angular/common';
import { Component, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { routeColor } from '../../core/gtfs';
import { LineStats } from '../../core/models/models';
import { Sort, SortKey } from './sort';

/** The line statistics table. Sortable by clicking a header when a `sort` is given; otherwise a fixed list. */
@Component({
  selector: 'app-line-stats-table',
  imports: [DecimalPipe, RouterLink],
  templateUrl: './line-stats-table.html',
  styleUrl: './line-stats-table.scss',
})
export class LineStatsTable {
  readonly lines = input.required<LineStats[]>();
  /** Weekday service, for the link to the line's trips. */
  readonly serviceId = input<string | null>(null);
  readonly loading = input(false);
  /** Current sort; null makes the headers plain labels. */
  readonly sort = input<Sort | null>(null);
  /** Text when there are no lines. */
  readonly empty = input('No lines in this timetable.');
  readonly sortChange = output<SortKey>();

  protected readonly columns: { key: SortKey; label: string }[] = [
    { key: 'lengthKm', label: 'Length' },
    { key: 'shortestTravelSeconds', label: 'Shortest' },
    { key: 'longestTravelSeconds', label: 'Longest' },
    { key: 'peakSpeedKmh', label: 'Peak' },
    { key: 'offPeakSpeedKmh', label: 'Off-peak' },
    { key: 'peakIntervalMinutes', label: 'Peak' },
    { key: 'offPeakIntervalMinutes', label: 'Off-peak' },
    { key: 'peakVehicles', label: 'Peak' },
    { key: 'offPeakVehicles', label: 'Off-peak' },
  ];

  protected readonly routeColor = routeColor;

  protected ariaSort(key: SortKey): 'ascending' | 'descending' | null {
    const s = this.sort();
    return s?.key === key ? (s.descending ? 'descending' : 'ascending') : null;
  }

  /** Seconds → whole minutes. */
  protected minutes(seconds: number | null): number | null {
    return seconds === null ? null : Math.round(seconds / 60);
  }
}
