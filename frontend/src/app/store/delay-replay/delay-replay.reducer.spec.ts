import { ReplayResponse } from '../../core/models/models';
import { DelayReplayActions } from './delay-replay.actions';
import { delayReplayFeature } from './delay-replay.reducer';

describe('delay replay reducer', () => {
  const reduce = delayReplayFeature.reducer;
  const query = { from: '2026-11-16T10:00:00Z', to: '2026-11-16T11:00:00Z', routes: ['103'] };
  const replay: ReplayResponse = {
    from: query.from,
    to: query.to,
    stepSeconds: 60,
    routes: ['103'],
    vehicles: [],
    lines: [],
    brigades: [],
    frames: [],
  };

  it('keeps the previous replay while loading a new one', () => {
    let state = reduce(undefined, DelayReplayActions.load({ query }));
    state = reduce(state, DelayReplayActions.replayLoaded({ replay }));
    state = reduce(state, DelayReplayActions.load({ query: { ...query, routes: [] } }));
    expect(state.loading).toBe(true);
    expect(state.replay).toBe(replay);
    expect(state.query?.routes).toEqual([]);
  });

  it('stores lines and errors', () => {
    let state = reduce(
      undefined,
      DelayReplayActions.routesLoaded({ routes: [{ route: '2', samples: 3 }] }),
    );
    state = reduce(
      state,
      DelayReplayActions.failure({ error: 'A replay can cover at most 7 days' }),
    );
    expect(state.routes).toHaveLength(1);
    expect(state.error).toBe('A replay can cover at most 7 days');
    expect(state.loading).toBe(false);
  });
});
