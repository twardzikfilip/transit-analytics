import { createActionGroup, props } from '@ngrx/store';
import { TripAnomalyReport, TripWindow } from '../../core/models/models';

export const TripAnomaliesActions = createActionGroup({
  source: 'Trip Anomalies',
  events: {
    Load: props<{ window: TripWindow }>(),
    Loaded: props<{ report: TripAnomalyReport }>(),
    Failure: props<{ error: string }>(),
  },
});
