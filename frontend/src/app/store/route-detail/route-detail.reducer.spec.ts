import { LineDetail, LinePerformance } from '../../core/models/models';
import { RouteDetailActions } from './route-detail.actions';
import { routeDetailFeature } from './route-detail.reducer';

const reducer = routeDetailFeature.reducer;
const performance = { fullRuns: 3 } as LinePerformance;

describe('routeDetail reducer', () => {
  it('keeps the previous detail while the next line loads, but clears its performance', () => {
    let state = reducer(undefined, RouteDetailActions.select({ line: '103' }));
    state = reducer(
      state,
      RouteDetailActions.detailLoaded({ detail: { line: '103' } as LineDetail }),
    );
    state = reducer(state, RouteDetailActions.performanceLoaded({ line: '103', performance }));
    expect(state.performance).toBe(performance);

    state = reducer(state, RouteDetailActions.select({ line: '5' }));
    expect(state.detail?.line).toBe('103');
    expect(state.performance).toBeNull();
    expect(state.loadingDetail).toBe(true);
    expect(state.loadingPerformance).toBe(true);
  });

  it("ignores another line's late performance", () => {
    let state = reducer(undefined, RouteDetailActions.select({ line: '5' }));
    state = reducer(state, RouteDetailActions.performanceLoaded({ line: '103', performance }));
    expect(state.performance).toBeNull();
    expect(state.loadingPerformance).toBe(true);
  });
});
