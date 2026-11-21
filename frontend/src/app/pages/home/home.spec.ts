import { renderPage, stateAfter, useSvgMaps } from '../../testing/page-testing';
import { livePositionsFeature } from '../../store/live-positions/live-positions.reducer';
import { positionHistoryFeature } from '../../store/position-history/position-history.reducer';
import { Home } from './home';

describe('Home page', () => {
  it('shows the live map, then the recorded positions with their own time window', () => {
    useSvgMaps();
    const el = renderPage(Home, {
      ...stateAfter(livePositionsFeature),
      ...stateAfter(positionHistoryFeature),
    }).el;
    expect(el.querySelector('app-live-map')).not.toBeNull();
    const history = el.querySelector('app-position-history')!;
    expect(history.querySelector('app-time-window')).not.toBeNull();
    // The time window belongs to the recorded positions, not to the live map.
    expect(el.querySelector('app-live-map app-time-window')).toBeNull();
  });
});
