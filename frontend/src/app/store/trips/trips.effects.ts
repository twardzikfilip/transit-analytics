import { inject } from '@angular/core';
import { Actions, createEffect } from '@ngrx/effects';
import { TripApi } from '../../core/api/api.services';
import { pagedLoadEffect } from '../paged';
import { TripsActions } from './trips.actions';

export const loadTrips = createEffect(
  (actions$ = inject(Actions), api = inject(TripApi)) =>
    pagedLoadEffect(TripsActions, (q) => api.list(q.page, q.size, q.sort, q.filters))(actions$),
  { functional: true },
);
