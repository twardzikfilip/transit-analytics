import { HttpErrorResponse } from '@angular/common/http';
import { Actions } from '@ngrx/effects';
import { of, throwError } from 'rxjs';
import { TripRunApi } from '../../core/api/api.services';
import { TripRunsActions } from './trip-runs.actions';
import { loadDay } from './trip-runs.effects';
import { tripRunsFeature } from './trip-runs.reducer';

describe("A brigade's day of trips", () => {
  const day = { serviceDay: '2026-11-23', brigade: '103-05', trips: [] };
  const ask = TripRunsActions.loadDay({ brigade: '103-05', date: null });
  const run = (api: unknown) => {
    const out: unknown[] = [];
    loadDay(of(ask) as unknown as Actions, api as TripRunApi).subscribe((a) => out.push(a));
    return out;
  };

  it('is loaded, or keeps the error', () => {
    const api = { day: vi.fn(() => of(day)) };
    expect(run(api)).toEqual([TripRunsActions.dayLoaded({ day })]);
    expect(api.day).toHaveBeenCalledWith('103-05', null);
    const fails = () =>
      throwError(() => new HttpErrorResponse({ status: 500, error: { detail: 'Down' } }));
    expect(run({ day: fails })).toEqual([TripRunsActions.dayFailure({ error: 'Down' })]);
  });

  it("is kept apart from the Trips page's list", () => {
    let state = tripRunsFeature.reducer(undefined, ask);
    expect(state).toMatchObject({ loadingDay: true, loading: false });
    state = tripRunsFeature.reducer(state, TripRunsActions.dayLoaded({ day }));
    expect(state).toMatchObject({ day, loadingDay: false, page: null });
    state = tripRunsFeature.reducer(state, TripRunsActions.dayFailure({ error: 'Down' }));
    expect(state).toMatchObject({ day, error: 'Down' });
  });
});
