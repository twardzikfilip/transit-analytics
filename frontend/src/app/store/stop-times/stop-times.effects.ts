import { inject } from '@angular/core';
import { Actions, createEffect } from '@ngrx/effects';
import { StopTimeApi } from '../../core/api/api.services';
import { pagedLoadEffect } from '../paged';
import { StopTimesActions } from './stop-times.actions';

export const loadStopTimes = createEffect(
  (actions$ = inject(Actions), api = inject(StopTimeApi)) =>
    pagedLoadEffect(StopTimesActions, (q) => api.list(q.page, q.size, q.filters))(actions$),
  { functional: true },
);
