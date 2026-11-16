import { createActionGroup, props } from '@ngrx/store';
import { DelayBand, FleetSeries, Page, PositionSample, RouteActivity } from '../../core/models/models';

/** A time window ([from, to), ISO instants) and an optional line. */
export interface HistoryQuery {
  from: string;
  to: string;
  route: string | null;
}

export const PositionHistoryActions = createActionGroup({
  source: 'Position History',
  events: {
    /** Loads the chart series, the delay bands and the line list for a window. */
    Load: props<{ query: HistoryQuery }>(),
    'Fleet Loaded': props<{ series: FleetSeries }>(),
    'Routes Loaded': props<{ routes: RouteActivity[] }>(),
    'Delays Loaded': props<{ delays: DelayBand[] }>(),
    'Load Samples': props<{ query: HistoryQuery; page: number }>(),
    'Samples Loaded': props<{ samples: Page<PositionSample> }>(),
    Failure: props<{ error: string }>(),
  },
});
