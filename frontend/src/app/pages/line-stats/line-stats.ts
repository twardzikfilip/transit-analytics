import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { Store } from '@ngrx/store';
import { LineStatsActions } from '../../store/line-stats/line-stats.actions';
import { lineStatsFeature } from '../../store/line-stats/line-stats.reducer';
import { LineStatsTable } from './line-stats-table';
import { Sort, SortKey, nextSort, sortLines, topLines } from './sort';

/** Per-line statistics from the weekday timetable. */
@Component({
  selector: 'app-line-stats',
  imports: [DatePipe, LineStatsTable],
  templateUrl: './line-stats.html',
  styleUrl: './line-stats.scss',
})
export class LineStatsPage {
  private readonly store = inject(Store);

  protected readonly report = this.store.selectSignal(lineStatsFeature.selectReport);
  protected readonly loading = this.store.selectSignal(lineStatsFeature.selectLoading);
  protected readonly error = this.store.selectSignal(lineStatsFeature.selectError);

  protected readonly sort = signal<Sort>({ key: 'route', descending: false });
  protected readonly lines = computed(() => sortLines(this.report()?.lines ?? [], this.sort()));
  protected readonly peakText = computed(() => periodsText(this.report()?.peak));
  protected readonly offPeakText = computed(() => periodsText(this.report()?.offPeak));

  /**
   * Top 5 lists under the main table. Speed rankings use peak hours, when speed differs most between lines;
   * "frequent" means a short average interval.
   */
  protected readonly tops = computed(() => {
    const lines = this.report()?.lines ?? [];
    return [
      {
        title: 'Top 5 longest lines',
        description: 'By length of the main route.',
        lines: topLines(lines, 'lengthKm', true),
      },
      {
        title: 'Top 5 shortest lines',
        description: 'By length of the main route.',
        lines: topLines(lines, 'lengthKm', false),
      },
      {
        title: 'Top 5 fastest lines',
        description: 'By average speed in peak hours.',
        lines: topLines(lines, 'peakSpeedKmh', true),
      },
      {
        title: 'Top 5 slowest lines',
        description: 'By average speed in peak hours.',
        lines: topLines(lines, 'peakSpeedKmh', false),
      },
      {
        title: 'Top 5 most frequent lines in peak hours',
        description: 'Shortest average interval between departures in peak hours.',
        lines: topLines(lines, 'peakIntervalMinutes', false),
      },
      {
        title: 'Top 5 most frequent lines off-peak',
        description: 'Shortest average interval between departures off-peak.',
        lines: topLines(lines, 'offPeakIntervalMinutes', false),
      },
      {
        title: 'Top 5 most regular lines in peak hours',
        description:
          'Most evenly spread departures in peak hours (lowest coefficient of variation).',
        lines: topLines(lines, 'peakRegularityCv', false),
      },
      {
        title: 'Top 5 least regular lines in peak hours',
        description:
          'Most unevenly spread departures in peak hours (highest coefficient of variation).',
        lines: topLines(lines, 'peakRegularityCv', true),
      },
      {
        title: 'Top 5 least frequent lines in peak hours',
        description:
          'Longest average interval between departures in peak hours (lines with no peak departures are left out).',
        lines: topLines(lines, 'peakIntervalMinutes', true),
      },
    ];
  });

  constructor() {
    this.store.dispatch(LineStatsActions.load());
  }

  protected sortBy(key: SortKey): void {
    this.sort.update((current) => nextSort(current, key));
  }
}

/** "06:30–08:30 and 15:00–17:00". */
function periodsText(periods: { from: string; to: string }[] | undefined): string {
  return (periods ?? []).map((p) => `${p.from}–${p.to}`).join(' and ');
}
