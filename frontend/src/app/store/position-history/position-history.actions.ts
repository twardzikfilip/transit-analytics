import { createActionGroup, emptyProps, props } from '@ngrx/store';
import { DelayBand, FleetSeries, RouteActivity, StopDwell } from '../../core/models/models';

/** A time window ([from, to), ISO instants), and optionally a route and an operator (null for all). */
export interface HistoryQuery {
  /** Null for all time. */
  from: string | null;
  to: string;
  route: string | null;
  agency: string | null;
}

export const PositionHistoryActions = createActionGroup({
  source: 'Position History',
  events: {
    /** Loads the chart series, the delay bands, the route list and the stops' dwell for a window. */
    Load: props<{ query: HistoryQuery }>(),
    'Fleet Loaded': props<{ series: FleetSeries }>(),
    'Routes Loaded': props<{ routes: RouteActivity[] }>(),
    'Delays Loaded': props<{ delays: DelayBand[] }>(),
    'Dwell Loaded': props<{ dwell: StopDwell[] }>(),
    /** Loads the operators for the operator filter (once). */
    'Load Agencies': emptyProps(),
    'Agencies Loaded': props<{ agencies: string[] }>(),
    Failure: props<{ error: string }>(),
  },
});
