import { Component } from '@angular/core';
import { of } from 'rxjs';
import { GtfsApi } from '../core/api/api.services';
import { FeedCoverage, TimetableCoverage } from '../core/models/models';
import { renderPage } from '../testing/page-testing';
import { AssumedTimetable, assumedDays } from './assumed-timetable';

@Component({
  imports: [AssumedTimetable],
  template: '<app-assumed-timetable from="2026-11-30" to="2026-11-30" />',
})
class Host {}

const feed = (key: string, assumed: string[]): FeedCoverage => ({
  feed: key,
  lastDay: '2026-11-28',
  daysLeft: -2,
  ending: true,
  assumed,
});
const COVERAGE: TimetableCoverage = {
  today: '2026-11-30',
  warnDays: 5,
  feeds: [
    feed('bialystok', []),
    feed('wschodexpress', ['2026-11-29', '2026-11-30', '2026-12-01']),
    feed('turosn-koscielna', ['2026-11-29']),
  ],
};

describe('Assumed timetable', () => {
  it('finds the assumed days of the window, by day or instant', () => {
    expect(assumedDays(COVERAGE, '2026-11-30', '2026-12-05', null)).toEqual([
      { feed: 'wschodexpress', from: '2026-11-30', to: '2026-12-01' },
    ]);
    expect(
      assumedDays(COVERAGE, '2026-11-29T12:00:00Z', '2026-11-29T13:00:00Z', null),
    ).toHaveLength(2);
    expect(assumedDays(COVERAGE, '2026-11-29', '2026-11-29', 'turosn-koscielna')).toHaveLength(1);
    expect(assumedDays(COVERAGE, '2026-11-27', '2026-11-28', null)).toEqual([]);
    expect(assumedDays(null, '2026-11-29', '2026-11-29', null)).toEqual([]);
  });

  it('says whose timetable is assumed', () => {
    const { el } = renderPage(Host, {}, {}, [
      { provide: GtfsApi, useValue: { coverage: () => of(COVERAGE) } },
    ]);
    expect(el.textContent).toContain('Wschód Express');
    expect(el.textContent).not.toContain('Turośń');
  });
});
