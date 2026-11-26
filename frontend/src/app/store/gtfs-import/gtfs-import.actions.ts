import { createActionGroup, emptyProps, props } from '@ngrx/store';
import { GtfsFeed, GtfsImportResult } from '../../core/models/models';

export const GtfsImportActions = createActionGroup({
  source: 'GTFS Import',
  events: {
    'Load Feeds': emptyProps(),
    'Feeds Loaded': props<{ feeds: GtfsFeed[] }>(),
    'Feeds Failure': props<{ error: string }>(),
    'Import Feed': props<{ feed: string }>(),
    'Import Success': props<{ result: GtfsImportResult }>(),
    'Import Failure': props<{ error: string }>(),
  },
});
