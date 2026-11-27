import { Type } from '@angular/core';
import { Action } from '@ngrx/store';
import { BrigadesActions } from '../store/brigades/brigades.actions';
import { brigadesFeature } from '../store/brigades/brigades.reducer';
import { BusLanesActions } from '../store/bus-lanes/bus-lanes.actions';
import { busLanesFeature } from '../store/bus-lanes/bus-lanes.reducer';
import { CityRailActions } from '../store/city-rail/city-rail.actions';
import { cityRailFeature } from '../store/city-rail/city-rail.reducer';
import { CorridorsActions } from '../store/corridors/corridors.actions';
import { corridorsFeature } from '../store/corridors/corridors.reducer';
import { DelayGenerationActions } from '../store/delay-generation/delay-generation.actions';
import { delayGenerationFeature } from '../store/delay-generation/delay-generation.reducer';
import { FleetActions } from '../store/fleet/fleet.actions';
import { fleetFeature } from '../store/fleet/fleet.reducer';
import { HotspotsActions } from '../store/hotspots/hotspots.actions';
import { hotspotsFeature } from '../store/hotspots/hotspots.reducer';
import { LineStatsActions } from '../store/line-stats/line-stats.actions';
import { lineStatsFeature } from '../store/line-stats/line-stats.reducer';
import { RouteDetailActions } from '../store/route-detail/route-detail.actions';
import { routeDetailFeature } from '../store/route-detail/route-detail.reducer';
import { StopsActions } from '../store/stops/stops.actions';
import { stopsFeature } from '../store/stops/stops.reducer';
import { TripAnomaliesActions } from '../store/trip-anomalies/trip-anomalies.actions';
import { tripAnomaliesFeature } from '../store/trip-anomalies/trip-anomalies.reducer';
import { renderPage, stateAfter, useSvgMaps } from '../testing/page-testing';
import { BrigadePage } from './brigades/brigade';
import { BrigadesPage } from './brigades/brigades';
import { BusLanesPage } from './bus-lanes/bus-lanes';
import { CityRail } from './city-rail/city-rail';
import { ProposalOverview } from './city-rail/proposal/proposal-overview';
import { Corridors } from './corridors/corridors';
import { DelayGeneration } from './delay-generation/delay-generation';
import { Fleet } from './fleet/fleet';
import { VehiclePage } from './fleet/vehicle';
import { Hotspots } from './hotspots/hotspots';
import { RoutesPage } from './routes/routes';
import { RoutesOverview } from './routes/routes-overview';
import { Stops } from './stops/stops';
import { TripAnomalies } from './trip-anomalies/trip-anomalies';

const WINDOW = { from: '2026-11-15T00:00:00Z', to: '2026-11-22T00:00:00Z' };

/** Each page while its data loads: [name, page, state, route parameters]. */
const LOADING: [string, Type<unknown>, Record<string, unknown>, Record<string, string>][] = [
  [
    'Brigades',
    BrigadesPage,
    stateAfter(brigadesFeature, BrigadesActions.loadList({ agency: null })),
    {},
  ],
  [
    'a brigade',
    BrigadePage,
    stateAfter(brigadesFeature, BrigadesActions.loadDay({ brigade: '103-05', date: null })),
    { brigade: '103-05' },
  ],
  [
    'Fleet',
    Fleet,
    stateAfter(fleetFeature, FleetActions.load({ window: WINDOW, agencies: [] })),
    {},
  ],
  [
    'a bus',
    VehiclePage,
    stateAfter(fleetFeature, FleetActions.loadVehicle({ id: 7, window: WINDOW })),
    { id: '7' },
  ],
  [
    'Abnormal trips',
    TripAnomalies,
    stateAfter(tripAnomaliesFeature, TripAnomaliesActions.load({ window: WINDOW })),
    {},
  ],
  [
    'Stop hotspots',
    Hotspots,
    stateAfter(hotspotsFeature, HotspotsActions.load({ window: WINDOW, line: null })),
    {},
  ],
  [
    'Bus lanes',
    BusLanesPage,
    stateAfter(busLanesFeature, BusLanesActions.load({ window: WINDOW })),
    {},
  ],
  [
    'Delay generation',
    DelayGeneration,
    stateAfter(delayGenerationFeature, DelayGenerationActions.load({ window: WINDOW })),
    {},
  ],
  ['City rail', CityRail, stateAfter(cityRailFeature, CityRailActions.load()), {}],
  [
    'City rail proposal',
    ProposalOverview,
    stateAfter(cityRailFeature, CityRailActions.loadProposal()),
    {},
  ],
  ['Corridors', Corridors, stateAfter(corridorsFeature, CorridorsActions.load()), {}],
  [
    'Routes',
    RoutesOverview,
    {
      ...stateAfter(routeDetailFeature, RouteDetailActions.loadLines()),
      ...stateAfter(lineStatsFeature, LineStatsActions.load()),
    },
    {},
  ],
  [
    'a route',
    RoutesPage,
    {
      ...stateAfter(routeDetailFeature, RouteDetailActions.select({ line: '103' })),
      ...stateAfter(lineStatsFeature),
    },
    { line: '103' },
  ],
  [
    'Stops',
    Stops,
    stateAfter(
      stopsFeature,
      StopsActions.load({ query: { page: 0, size: 20, sort: 'name', filters: {} } }) as Action,
    ),
    {},
  ],
];

describe('Pages while they load', () => {
  for (const [name, page, state, path] of LOADING) {
    it(`${name}: shows skeletons shaped like what is coming, announced as loading`, () => {
      useSvgMaps();
      const { el } = renderPage(page, state, {}, [], path);
      const skeletons = [...el.querySelectorAll('app-skeleton')];
      expect(skeletons.length, 'skeletons').toBeGreaterThan(0);
      expect(skeletons[0].getAttribute('role')).toBe('status');
      expect(skeletons[0].getAttribute('aria-busy')).toBe('true');
      expect(skeletons[0].querySelector('ngx-skeleton-loader')).not.toBeNull();
    });
  }

  it("doesn't show skeletons once loading is over, even without data (a failure is a toast)", () => {
    const { el } = renderPage(
      Fleet,
      stateAfter(
        fleetFeature,
        FleetActions.load({ window: WINDOW, agencies: [] }),
        FleetActions.failure({ error: 'Down' }),
      ),
    );
    expect(el.querySelector('app-skeleton')).toBeNull();
  });
});
