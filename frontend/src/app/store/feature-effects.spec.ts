import { HttpErrorResponse } from '@angular/common/http';
import { Actions } from '@ngrx/effects';
import { Observable, Subject, of, throwError } from 'rxjs';
import { PositionHistoryApi, VehicleApi } from '../core/api/api.services';
import { Vehicle } from '../core/models/models';
import { DelayReplayActions } from './delay-replay/delay-replay.actions';
import * as replayEffects from './delay-replay/delay-replay.effects';
import { PositionHistoryActions } from './position-history/position-history.actions';
import * as historyEffects from './position-history/position-history.effects';
import { VehiclesActions } from './vehicles/vehicles.actions';
import {
  createVehicle,
  deleteVehicle,
  loadVehicles,
  updateVehicle,
} from './vehicles/vehicles.effects';

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

describe('vehicle effects', () => {
  const vehicle: Vehicle = {
    id: 1,
    vehicleNumber: '230',
    type: 'BUS',
    capacity: null,
    agency: null,
    model: null,
  };
  const payload = { vehicleNumber: '230', type: 'BUS', capacity: null, agency: null, model: null };

  it('loads, creates, updates and deletes through the API', () => {
    const api = {
      list: vi.fn(() =>
        of({ content: [vehicle], page: { number: 0, size: 500, totalElements: 1, totalPages: 1 } }),
      ),
      create: () => of(vehicle),
      update: () => of(vehicle),
      delete: () => of(undefined),
    } as unknown as VehicleApi;

    expect(run(loadVehicles, api, VehiclesActions.load())).toEqual([
      VehiclesActions.loadSuccess({ vehicles: [vehicle] }),
    ]);
    expect(api.list).toHaveBeenCalledWith(0, 500, 'vehicleNumber');
    expect(run(createVehicle, api, VehiclesActions.create({ vehicle: payload }))).toEqual([
      VehiclesActions.createSuccess({ vehicle }),
    ]);
    expect(run(updateVehicle, api, VehiclesActions.update({ id: 1, vehicle: payload }))).toEqual([
      VehiclesActions.updateSuccess({ vehicle }),
    ]);
    expect(run(deleteVehicle, api, VehiclesActions.delete({ id: 1 }))).toEqual([
      VehiclesActions.deleteSuccess({ id: 1 }),
    ]);
  });

  it("reports the server's message when a request fails", () => {
    const api = { list: fail, create: fail, update: fail, delete: fail } as unknown as VehicleApi;
    const failure = VehiclesActions.failure({ error: 'Database down' });
    expect(run(loadVehicles, api, VehiclesActions.load())).toEqual([failure]);
    expect(run(deleteVehicle, api, VehiclesActions.delete({ id: 1 }))).toEqual([failure]);
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
      query.from,
      query.to,
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
    expect(api.replay).toHaveBeenCalledWith(query.from, query.to, ['103', '5']);
    expect(run(replayEffects.loadRoutes, api, load)).toEqual([
      DelayReplayActions.routesLoaded({ routes: [] }),
    ]);
  });
});
