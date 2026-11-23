import { of } from 'rxjs';
import { TripAnomalyApi } from '../../core/api/api.services';
import { FaultyTracker, FaultyTrackers } from '../../core/models/models';
import { renderPage, stateAfter, text, useSvgMaps } from '../../testing/page-testing';
import { livePositionsFeature } from '../../store/live-positions/live-positions.reducer';
import { positionHistoryFeature } from '../../store/position-history/position-history.reducer';
import { FaultyTrackers as FaultyTrackersTile } from './faulty-trackers';
import { Home } from './home';

describe('Home page', () => {
  it('shows the faulty trackers, the live map, then the recorded positions with their own time window', () => {
    useSvgMaps();
    const el = renderPage(
      Home,
      {
        ...stateAfter(livePositionsFeature),
        ...stateAfter(positionHistoryFeature),
      },
      {},
      [{ provide: TripAnomalyApi, useValue: { faultyTrackers: () => of() } }],
    ).el;
    expect(el.querySelector('app-faulty-trackers')).not.toBeNull();
    expect(el.querySelector('app-live-map')).not.toBeNull();
    const history = el.querySelector('app-position-history')!;
    expect(history.querySelector('app-time-window')).not.toBeNull();
    // The time window belongs to the recorded positions, not to the live map.
    expect(el.querySelector('app-live-map app-time-window')).toBeNull();
  });
});

describe('Faulty trackers tile', () => {
  const report = (trackers: FaultyTracker[]): FaultyTrackers => ({
    from: '2026-11-16T12:00:00Z',
    to: '2026-11-23T12:00:00Z',
    minDays: 3,
    minTrips: 5,
    minSharePercent: 10,
    trackers,
  });

  it('lists the buses with a red border, each with its trips, days, lines and a link', () => {
    const tracker: FaultyTracker = {
      vehicleId: 46,
      bus: '046',
      noSignalTrips: 43,
      trips: 129,
      days: 4,
      lastNoSignal: '2026-11-22T10:00:00Z',
      lines: ['6', '104'],
    };
    const { el } = renderPage(FaultyTrackersTile, {}, {}, [
      { provide: TripAnomalyApi, useValue: { faultyTrackers: () => of(report([tracker])) } },
    ]);
    expect(el.querySelector('.tile.alert')).not.toBeNull();
    expect(text(el.querySelector('h2'))).toBe('Buses with a possibly faulty tracker 1');
    const item = el.querySelector('li')!;
    expect(item.querySelector('a.bus')!.getAttribute('href')).toBe('/vehicles/46');
    expect(text(item)).toContain('43 of 129 trips (33%)');
    expect(text(item)).toContain('on 4 days');
    expect([...item.querySelectorAll('.route-badge')].map(text)).toEqual(['6', '104']);
  });

  it('says when no bus is listed, without the red border', () => {
    const { el } = renderPage(FaultyTrackersTile, {}, {}, [
      { provide: TripAnomalyApi, useValue: { faultyTrackers: () => of(report([])) } },
    ]);
    expect(el.querySelector('.tile.alert')).toBeNull();
    expect(text(el.querySelector('.tile p:last-child'))).toContain('No bus lost its signal');
  });
});
