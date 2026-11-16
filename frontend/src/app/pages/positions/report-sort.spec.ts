import {
  DEFAULT_REPORT_SORT,
  formatReportSort,
  nextReportSort,
  parseReportSort,
} from './report-sort';

describe('position report sorting', () => {
  it('reads and writes the URL form', () => {
    expect(parseReportSort('delay,desc')).toEqual({ key: 'delay', descending: true });
    expect(parseReportSort('line,asc')).toEqual({ key: 'line', descending: false });
    expect(parseReportSort(null)).toEqual(DEFAULT_REPORT_SORT);
    expect(parseReportSort('latitude,asc')).toEqual(DEFAULT_REPORT_SORT);
    expect(formatReportSort({ key: 'nextStop', descending: false })).toBe('nextStop,asc');
  });

  it('starts time and delay with the largest first, text A–Z, and flips the active column', () => {
    expect(nextReportSort(DEFAULT_REPORT_SORT, 'delay')).toEqual({
      key: 'delay',
      descending: true,
    });
    expect(nextReportSort(DEFAULT_REPORT_SORT, 'line')).toEqual({ key: 'line', descending: false });
    expect(nextReportSort(DEFAULT_REPORT_SORT, 'time')).toEqual({ key: 'time', descending: false });
  });
});
