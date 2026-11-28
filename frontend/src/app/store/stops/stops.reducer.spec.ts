import { Stop } from '../../core/models/models';
import { PageQuery } from '../paged';
import { StopsActions } from './stops.actions';
import { stopsFeature } from './stops.reducer';

describe('stops reducer (paged)', () => {
  const reduce = stopsFeature.reducer;
  const stop = (id: number, name: string): Stop => ({
    id,
    feed: 'bialystok',
    code: String(id),
    name,
    latitude: 53.13,
    longitude: 23.16,
    zoneId: '1',
  });
  const query: PageQuery = { page: 1, size: 2, filters: { q: 'dw' } };
  const meta = { size: 2, number: 1, totalElements: 5, totalPages: 3 };

  it('remembers the query, then holds exactly the loaded page', () => {
    let state = reduce(undefined, StopsActions.load({ query }));
    expect(state.loading).toBe(true);
    expect(state.query).toEqual(query);

    state = reduce(
      state,
      StopsActions.loadSuccess({ result: { content: [stop(1, 'A'), stop(2, 'B')], page: meta } }),
    );
    expect(state.ids).toEqual([1, 2]);
    expect(state.page).toEqual(meta);
    expect(state.loading).toBe(false);

    state = reduce(
      state,
      StopsActions.loadSuccess({
        result: { content: [stop(3, 'C')], page: { ...meta, number: 2 } },
      }),
    );
    expect(state.ids).toEqual([3]);
  });

  it('records failures and clears them on the next load', () => {
    let state = reduce(undefined, StopsActions.failure({ error: 'Boom' }));
    expect(state.error).toBe('Boom');
    state = reduce(state, StopsActions.load({ query }));
    expect(state.error).toBeNull();
  });
});
