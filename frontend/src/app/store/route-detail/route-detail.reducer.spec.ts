import { RouteDetail, RoutePerformance } from '../../core/models/models';
import { RouteDetailActions } from './route-detail.actions';
import { routeDetailFeature } from './route-detail.reducer';

const reducer = routeDetailFeature.reducer;
const performance = { fullRuns: 3 } as RoutePerformance;

describe('routeDetail reducer', () => {
  it('keeps the previous detail while the next line loads, but clears its performance', () => {
    let state = reducer(undefined, RouteDetailActions.select({ route: '103' }));
    state = reducer(
      state,
      RouteDetailActions.detailLoaded({ detail: { route: '103' } as RouteDetail }),
    );
    state = reducer(state, RouteDetailActions.performanceLoaded({ route: '103', performance }));
    expect(state.performance).toBe(performance);

    state = reducer(state, RouteDetailActions.select({ route: '5' }));
    expect(state.detail?.route).toBe('103');
    expect(state.performance).toBeNull();
    expect(state.loadingDetail).toBe(true);
    expect(state.loadingPerformance).toBe(true);
  });

  it("ignores another line's late performance", () => {
    let state = reducer(undefined, RouteDetailActions.select({ route: '5' }));
    state = reducer(state, RouteDetailActions.performanceLoaded({ route: '103', performance }));
    expect(state.performance).toBeNull();
    expect(state.loadingPerformance).toBe(true);
  });
});
