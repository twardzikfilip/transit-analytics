import { HttpErrorResponse } from '@angular/common/http';
import { Actions } from '@ngrx/effects';
import { Observable, Subject, of, throwError } from 'rxjs';
import { LineDetailApi } from '../../core/api/api.services';
import { LineDetail, LinePerformance } from '../../core/models/models';
import { RouteDetailActions } from './route-detail.actions';
import { loadDetail, loadLines, loadPerformance } from './route-detail.effects';

/** Collects what an effect emits. */
function collect(effect$: Observable<unknown>): unknown[] {
  const out: unknown[] = [];
  effect$.subscribe((a) => out.push(a));
  return out;
}

describe('route detail effects', () => {
  it('loads the line list, and reports a failure with the backend message', () => {
    const api = { lines: () => of([{ id: 1, line: '103', name: '103', subtype: 'suburban' }]) } as unknown as LineDetailApi;
    const actions = new Subject<unknown>();
    const out = collect(loadLines(actions as unknown as Actions, api));
    actions.next(RouteDetailActions.loadLines());
    expect(out[0]).toEqual(RouteDetailActions.linesLoaded({ lines: [{ id: 1, line: '103', name: '103', subtype: 'suburban' }] }));

    const failing = {
      lines: () => throwError(() => new HttpErrorResponse({ status: 500, error: { detail: 'Database down' } })),
    } as unknown as LineDetailApi;
    const actions2 = new Subject<unknown>();
    const out2 = collect(loadLines(actions2 as unknown as Actions, failing));
    actions2.next(RouteDetailActions.loadLines());
    expect(out2[0]).toEqual(RouteDetailActions.failure({ error: 'Database down' }));
  });

  it('cancels the previous line when another is picked', () => {
    const responses: Record<string, Subject<LineDetail>> = { '103': new Subject(), '5': new Subject() };
    const api = { detail: (line: string) => responses[line] } as unknown as LineDetailApi;
    const actions = new Subject<unknown>();
    const out = collect(loadDetail(actions as unknown as Actions, api));

    actions.next(RouteDetailActions.select({ line: '103' }));
    actions.next(RouteDetailActions.select({ line: '5' }));
    responses['103'].next({ line: '103' } as LineDetail);
    responses['5'].next({ line: '5' } as LineDetail);

    expect(out).toEqual([RouteDetailActions.detailLoaded({ detail: { line: '5' } as LineDetail })]);
  });

  it('tags the performance with its line, so a late answer for another line is ignored', () => {
    const performance = { fullRuns: 3 } as LinePerformance;
    const api = { performance: () => of(performance) } as unknown as LineDetailApi;
    const actions = new Subject<unknown>();
    const out = collect(loadPerformance(actions as unknown as Actions, api));
    actions.next(RouteDetailActions.select({ line: '10' }));
    expect(out[0]).toEqual(RouteDetailActions.performanceLoaded({ line: '10', performance }));
  });
});
