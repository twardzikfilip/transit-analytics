import { inject } from '@angular/core';
import { Actions, createEffect } from '@ngrx/effects';
import { StopApi } from '../../core/api/api.services';
import { pagedLoadEffect } from '../paged';
import { StopsActions } from './stops.actions';

export const loadStops = createEffect(
  (actions$ = inject(Actions), api = inject(StopApi)) =>
    pagedLoadEffect(StopsActions, (q) => api.list(q.page, q.size, q.sort, q.filters))(actions$),
  { functional: true },
);
