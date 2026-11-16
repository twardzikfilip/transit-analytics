import { createActionGroup, emptyProps, props } from '@ngrx/store';
import { GtfsImportResult } from '../../core/models/models';

export const GtfsImportActions = createActionGroup({
  source: 'GTFS Import',
  events: {
    'Import Bialystok': emptyProps(),
    'Import Success': props<{ result: GtfsImportResult }>(),
    'Import Failure': props<{ error: string }>(),
  },
});
