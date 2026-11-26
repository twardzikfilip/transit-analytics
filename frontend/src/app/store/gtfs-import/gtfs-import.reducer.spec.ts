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
    unchanged: false,
  };

  it('tracks which feed is importing and keeps the last result', () => {
    let state = reduce(undefined, GtfsImportActions.importFeed({ feed: 'bialystok' }));
    expect(state.running).toBe('bialystok');

    state = reduce(state, GtfsImportActions.importSuccess({ result }));
    expect(state).toEqual({ feeds: null, running: null, result, error: null });

    state = reduce(state, GtfsImportActions.importFeed({ feed: 'rail' }));
    state = reduce(
      state,
      GtfsImportActions.importFailure({ error: 'Live data source unavailable' }),
    );
    expect(state.running).toBeNull();
    expect(state.error).toBe('Live data source unavailable');
    expect(state.result).toBe(result);
  });

  it('keeps the feeds, and none after a failure', () => {
    const feeds = [{ key: 'rail', name: 'Trains', source: 'https://x', last: null }];
    expect(reduce(undefined, GtfsImportActions.feedsLoaded({ feeds })).feeds).toBe(feeds);
    expect(reduce(undefined, GtfsImportActions.feedsFailure({ error: 'x' })).feeds).toEqual([]);
  });
});
