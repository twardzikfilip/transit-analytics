import { Page, Stop, StopTime, Trip } from '../core/models/models';
import { RoutesActions } from '../store/routes/routes.actions';
import { routesFeature } from '../store/routes/routes.reducer';
import { StopTimesActions } from '../store/stop-times/stop-times.actions';
import { stopTimesFeature } from '../store/stop-times/stop-times.reducer';
import { StopsActions } from '../store/stops/stops.actions';
import { stopsFeature } from '../store/stops/stops.reducer';
import { TripsActions } from '../store/trips/trips.actions';
import { tripsFeature } from '../store/trips/trips.reducer';
import { renderPage, stateAfter, text } from '../testing/page-testing';
import { StopTimes } from './stop-times/stop-times';
import { Stops } from './stops/stops';
import { Trips } from './trips/trips';

function page<T>(content: T[], number = 0, totalElements = content.length): Page<T> {
  return {
    content,
    page: { number, size: 50, totalElements, totalPages: Math.ceil(totalElements / 50) },
  };
}

const stop = (id: number, name: string, zoneId = '1') =>
  ({ id, gtfsId: `${id}`, code: `${id}`, name, zoneId, latitude: 53.1, longitude: 23.1 }) as Stop;

describe('timetable list pages', () => {
  it('Stops: loads the page in the URL with its filters, numbering rows across pages', () => {
    const { el, store } = renderPage(
      Stops,
      stateAfter(
        stopsFeature,
        StopsActions.loadSuccess({ result: page([stop(1, 'Rynek'), stop(2, 'Dworzec')], 1, 52) }),
      ),
      { page: '2', zoneId: '2', q: 'ry' },
    );

    expect(store.dispatch).toHaveBeenCalledWith(
      StopsActions.load({
        query: { page: 1, size: 50, sort: 'name', filters: { q: 'ry', zoneId: '2' } },
      }),
    );
    expect([...el.querySelectorAll('tbody tr td:first-child')].map(text)).toEqual(['51', '52']);
    expect(text(el.querySelector('app-pager'))).toContain('Page 2 of 2');
  });

  it('Stops: loads again when the URL changes', () => {
    const { store, setQuery } = renderPage(Stops, stateAfter(stopsFeature));
    setQuery({ zoneId: '1' });
    expect(store.dispatch).toHaveBeenLastCalledWith(
      StopsActions.load({
        query: { page: 0, size: 50, sort: 'name', filters: { q: null, zoneId: '1' } },
      }),
    );
  });

  it('Trips: loads the routes for the picker once, and the trips of the route in the URL', () => {
    const trip = {
      id: 9,
      gtfsId: '103-01_1',
      routeId: 17,
      routeName: '103',
      headsign: 'Choroszcz',
      direction: 0,
    } as Trip;
    const { el, store } = renderPage(
      Trips,
      {
        ...stateAfter(tripsFeature, TripsActions.loadSuccess({ result: page([trip]) })),
        ...stateAfter(routesFeature),
      },
      { routeId: '17' },
    );

    expect(store.dispatch).toHaveBeenCalledWith(RoutesActions.load());
    expect(store.dispatch).toHaveBeenCalledWith(
      TripsActions.load({
        query: {
          page: 0,
          size: 50,
          sort: 'id',
          filters: { routeId: '17', direction: null, serviceId: null, q: null },
        },
      }),
    );
    expect(text(el.querySelector('tbody'))).toContain('Choroszcz');
  });

  it('Trips: does not reload routes it already has', () => {
    const route = { id: 17, gtfsId: '103', name: '103' } as never;
    const { store } = renderPage(Trips, {
      ...stateAfter(tripsFeature),
      ...stateAfter(routesFeature, RoutesActions.loadSuccess({ routes: [route] })),
    });
    expect(store.dispatch).not.toHaveBeenCalledWith(RoutesActions.load());
  });

  it('Stop times: one trip in stop order, with its boarding rules', () => {
    const st = (seq: number, pickupType: number | null, dropOffType: number | null) =>
      ({
        id: seq,
        tripId: 5,
        tripHeadsign: 'Choroszcz',
        routeName: '103',
        stopId: 7,
        stopName: 'Rynek',
        stopSequence: seq,
        departureTime: '25:10:00',
        arrivalTime: '25:10:00',
        pickupType,
        dropOffType,
      }) as StopTime;
    const state = stateAfter(
      stopTimesFeature,
      StopTimesActions.loadSuccess({ result: page([st(1, 3, 3), st(2, 1, 0)]) }),
    );

    const trip = renderPage(StopTimes, state, { tripId: '5' });
    expect(trip.store.dispatch).toHaveBeenCalledWith(
      StopTimesActions.load({
        query: { page: 0, size: 50, sort: 'stopSequence', filters: { tripId: '5', stopId: null } },
      }),
    );
    expect(text(trip.el.querySelector('h1'))).toBe('Trip 103 → Choroszcz');
    expect(text(trip.el.querySelector('tbody'))).toContain('Stops on request');
    expect(text(trip.el.querySelector('tbody'))).toContain('boarding none');
  });

  it('Stop times: a stop shows its departures by time', () => {
    const board = renderPage(StopTimes, stateAfter(stopTimesFeature), { stopId: '7' });
    expect(board.store.dispatch).toHaveBeenCalledWith(
      StopTimesActions.load({
        query: {
          page: 0,
          size: 50,
          sort: ['departureTime', 'id'],
          filters: { tripId: null, stopId: '7' },
        },
      }),
    );
    expect(text(board.el.querySelector('h1'))).toBe('Departures');
  });
});
