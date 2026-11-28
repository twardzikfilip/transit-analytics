import { HttpErrorResponse } from '@angular/common/http';
import { Actions } from '@ngrx/effects';
import { Observable, Subject, of, throwError } from 'rxjs';
import { RouteDetailApi } from '../../core/api/api.services';
import { RouteDetail, RoutePerformance } from '../../core/models/models';
import { RouteDetailActions } from './route-detail.actions';
import { loadDetail, loadRoutes, loadPerformance } from './route-detail.effects';

/** Collects what an effect emits. */
function collect(effect$: Observable<unknown>): unknown[] {
  const out: unknown[] = [];
  effect$.subscribe((a) => out.push(a));
  return out;
}

describe('route detail effects', () => {
  it('loads the line list, and reports a failure with the backend message', () => {
    const api = {
      routes: () =>
        of([
          {
            id: 1,
            route: '103',
            name: '103',
            subtype: 'suburban',
            geometry: null,
            capacityClass: null,
            operatingAgency: null,
          },
        ]),
    } as unknown as RouteDetailApi;
    const actions = new Subject<unknown>();
    const out = collect(loadRoutes(actions as unknown as Actions, api));
    actions.next(RouteDetailActions.loadRoutes());
    expect(out[0]).toEqual(
      RouteDetailActions.routesLoaded({
        routes: [
          {
            id: 1,
            route: '103',
            name: '103',
            subtype: 'suburban',
            geometry: null,
            capacityClass: null,
            operatingAgency: null,
          },
        ],
      }),
    );

    const failing = {
      routes: () =>
        throwError(
          () => new HttpErrorResponse({ status: 500, error: { detail: 'Database down' } }),
        ),
    } as unknown as RouteDetailApi;
    const actions2 = new Subject<unknown>();
    const out2 = collect(loadRoutes(actions2 as unknown as Actions, failing));
    actions2.next(RouteDetailActions.loadRoutes());
    expect(out2[0]).toEqual(RouteDetailActions.failure({ error: 'Database down' }));
  });

  it('cancels the previous line when another is picked', () => {
    const responses: Record<string, Subject<RouteDetail>> = {
      '103': new Subject(),
      '5': new Subject(),
    };
    const api = { detail: (route: string) => responses[route] } as unknown as RouteDetailApi;
    const actions = new Subject<unknown>();
    const out = collect(loadDetail(actions as unknown as Actions, api));

    actions.next(RouteDetailActions.select({ route: '103' }));
    actions.next(RouteDetailActions.select({ route: '5' }));
    responses['103'].next({ route: '103' } as RouteDetail);
    responses['5'].next({ route: '5' } as RouteDetail);

    expect(out).toEqual([
      RouteDetailActions.detailLoaded({ detail: { route: '5' } as RouteDetail }),
    ]);
  });

  it('tags the performance with its line, so a late answer for another line is ignored', () => {
    const performance = { fullRuns: 3 } as RoutePerformance;
    const api = { performance: () => of(performance) } as unknown as RouteDetailApi;
    const actions = new Subject<unknown>();
    const out = collect(loadPerformance(actions as unknown as Actions, api));
    actions.next(RouteDetailActions.select({ route: '10' }));
    expect(out[0]).toEqual(RouteDetailActions.performanceLoaded({ route: '10', performance }));
  });
});
