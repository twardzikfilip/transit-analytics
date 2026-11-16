import { GtfsImportActions } from './gtfs-import.actions';
import { gtfsImportFeature } from './gtfs-import.reducer';

describe('GTFS import reducer', () => {
  const reduce = gtfsImportFeature.reducer;
  const result = {
    feed: 'bialystok',
    source: 'https://example.org/gtfs.zip',
    downloadedBytes: 4_635_313,
    importedAt: '2026-11-16T10:43:06Z',
    durationMs: 30_517,
    rows: { route: 55, stop: 1083 },
    skipped: {},
    ignoredFiles: [],
  };

  it('tracks a running import and keeps the last result', () => {
    let state = reduce(undefined, GtfsImportActions.importBialystok());
    expect(state.running).toBe(true);

    state = reduce(state, GtfsImportActions.importSuccess({ result }));
    expect(state).toEqual({ running: false, result, error: null });

    state = reduce(state, GtfsImportActions.importBialystok());
    state = reduce(state, GtfsImportActions.importFailure({ error: 'Live data source unavailable' }));
    expect(state.running).toBe(false);
    expect(state.error).toBe('Live data source unavailable');
    expect(state.result).toBe(result);
  });
});
