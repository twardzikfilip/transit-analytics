import { createActionGroup, props } from '@ngrx/store';
import { TripAnomalyReport, WindowQuery } from '../../core/models/models';

export const TripAnomaliesActions = createActionGroup({
  source: 'Trip Anomalies',
  events: {
    Load: props<{ window: WindowQuery }>(),
    Loaded: props<{ report: TripAnomalyReport }>(),
    Failure: props<{ error: string }>(),
  },
});
