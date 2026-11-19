import { HttpErrorResponse } from '@angular/common/http';
import { Actions } from '@ngrx/effects';
import { Observable, Subject, of, throwError } from 'rxjs';
import { FleetApi, PositionHistoryApi } from '../core/api/api.services';
import { DelayReplayActions } from './delay-replay/delay-replay.actions';
import * as replayEffects from './delay-replay/delay-replay.effects';
import { PositionHistoryActions } from './position-history/position-history.actions';
import * as historyEffects from './position-history/position-history.effects';
import { FleetActions } from './fleet/fleet.actions';
import * as fleetEffects from './fleet/fleet.effects';
import { fleetFeature } from './fleet/fleet.reducer';

function run<A>(
  effect: (actions$: Actions, api: A) => Observable<unknown>,
  api: A,
  ...dispatched: unknown[]
) {
  const actions = new Subject<unknown>();
  const out: unknown[] = [];
  effect(actions as unknown as Actions, api).subscribe((a) => out.push(a));
  dispatched.forEach((a) => actions.next(a));
  return out;
}

const fail = () =>
  throwError(() => new HttpErrorResponse({ status: 500, error: { detail: 'Database down' } }));

describe('fleet effects and reducer', () => {
  const window = { from: 'A', to: 'B' };
  const report = { inUse: 1 } as never;
  const vehicle = { vehicle: { id: 7 } } as never;

  it('loads the fleet and a bus, and does nothing when the bus is cleared', () => {
    const api = {
      report: vi.fn(() => of(report)),
      vehicle: vi.fn(() => of(vehicle)),
    } as unknown as FleetApi;

    expect(run(fleetEffects.load, api, FleetActions.load({ window, agencies: ['BKM'] }))).toEqual([
      FleetActions.loaded({ report }),
    ]);
    expect(api.report).toHaveBeenCalledWith(window, ['BKM']);
    expect(run(fleetEffects.loadVehicle, api, FleetActions.loadVehicle({ id: 7, window }))).toEqual([
      FleetActions.vehicleLoaded({ vehicle }),
    ]);
    expect(api.vehicle).toHaveBeenCalledWith(7, window);
    expect(run(fleetEffects.loadVehicle, api, FleetActions.clearVehicle())).toEqual([]);
  });

  it("reports the server's message when a request fails", () => {
    const api = { report: fail, vehicle: fail } as unknown as FleetApi;
    expect(run(fleetEffects.load, api, FleetActions.load({ window, agencies: [] }))).toEqual([
      FleetActions.failure({ error: 'Database down' }),
    ]);
    expect(run(fleetEffects.loadVehicle, api, FleetActions.loadVehicle({ id: 7, window }))).toEqual([
      FleetActions.vehicleFailure({ error: 'Database down' }),
    ]);
  });

  it('keeps the bus shown while it reloads, and drops it for another bus or when cleared', () => {
    const reduce = (...actions: Parameters<typeof fleetFeature.reducer>[1][]) =>
      actions.reduce(fleetFeature.reducer, fleetFeature.reducer(undefined, { type: '@@init' }));
    const loaded = [FleetActions.loadVehicle({ id: 7, window }), FleetActions.vehicleLoaded({ vehicle })];

    expect(reduce(...loaded, FleetActions.loadVehicle({ id: 7, window }))).toMatchObject({
      vehicle,
      loadingVehicle: true,
    });
    expect(reduce(...loaded, FleetActions.loadVehicle({ id: 8, window })).vehicle).toBeNull();
    expect(reduce(...loaded, FleetActions.clearVehicle())).toMatchObject({ vehicle: null, loadingVehicle: false });
    expect(reduce(FleetActions.loadVehicle({ id: 7, window }), FleetActions.vehicleFailure({ error: 'x' })))
      .toMatchObject({ loadingVehicle: false, vehicleError: 'x' });
    expect(reduce(FleetActions.load({ window, agencies: [] }), FleetActions.failure({ error: 'y' })))
      .toMatchObject({ loading: false, error: 'y' });
  });
});

describe('position history and delay replay effects', () => {
  const query = { from: '2026-11-16T11:00:00Z', to: '2026-11-16T12:00:00Z', route: '103' };

  it('loads the fleet, delays, routes and a page of samples for a window', () => {
    const api = {
      fleet: () => of({ stepSeconds: 60, points: [] }),
      delays: () => of([]),
      routes: () => of([]),
      samples: vi.fn(() =>
        of({ content: [], page: { number: 0, size: 50, totalElements: 0, totalPages: 0 } }),
      ),
    } as unknown as PositionHistoryApi;
    const load = PositionHistoryActions.load({ query });

    expect(run(historyEffects.loadFleet, api, load)).toEqual([
      PositionHistoryActions.fleetLoaded({ series: { stepSeconds: 60, points: [] } as never }),
    ]);
    expect(run(historyEffects.loadDelays, api, load)).toEqual([
      PositionHistoryActions.delaysLoaded({ delays: [] }),
    ]);
    expect(run(historyEffects.loadRoutes, api, load)).toEqual([
      PositionHistoryActions.routesLoaded({ routes: [] }),
    ]);
    run(
      historyEffects.loadSamples,
      api,
      PositionHistoryActions.loadSamples({
        query,
        page: 2,
        sort: 'delay,desc',
        excludeOutliers: true,
      }),
    );
    expect(api.samples).toHaveBeenCalledWith(
      query,
      '103',
      2,
      50,
      'delay,desc',
      true,
    );
  });

  it('turns failures into a failure action', () => {
    const api = { fleet: fail, replay: fail, routes: fail } as unknown as PositionHistoryApi;
    expect(run(historyEffects.loadFleet, api, PositionHistoryActions.load({ query }))).toEqual([
      PositionHistoryActions.failure({ error: 'Database down' }),
    ]);
    const replay = DelayReplayActions.load({
      query: { from: query.from, to: query.to, routes: ['103'] },
    });
    expect(run(replayEffects.loadReplay, api, replay)).toEqual([
      DelayReplayActions.failure({ error: 'Database down' }),
    ]);
  });

  it('loads the replay and the lines that ran in its window', () => {
    const raw = {
      from: query.from,
      to: query.to,
      stepSeconds: 60,
      routes: [],
      vehicles: [],
      lines: [],
      brigades: [],
      frames: [],
    };
    const api = {
      replay: vi.fn(() => of(raw)),
      routes: () => of([]),
    } as unknown as PositionHistoryApi;
    const load = DelayReplayActions.load({
      query: { from: query.from, to: query.to, routes: ['103', '5'] },
    });

    expect(run(replayEffects.loadReplay, api, load)).toEqual([
      DelayReplayActions.replayLoaded({ replay: raw }),
    ]);
    expect(api.replay).toHaveBeenCalledWith(
      { from: query.from, to: query.to, routes: ['103', '5'] },
      ['103', '5'],
    );
    expect(run(replayEffects.loadRoutes, api, load)).toEqual([
      DelayReplayActions.routesLoaded({ routes: [] }),
    ]);
  });
});
