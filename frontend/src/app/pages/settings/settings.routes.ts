import { Routes } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';
import * as gtfsImportEffects from '../../store/gtfs-import/gtfs-import.effects';
import { gtfsImportFeature } from '../../store/gtfs-import/gtfs-import.reducer';
import { Settings } from './settings';

/** Settings, with the GTFS import slice it uses, loaded on first visit. */
export const SETTINGS_ROUTES: Routes = [
  {
    path: '',
    providers: [provideState(gtfsImportFeature), provideEffects(gtfsImportEffects)],
    component: Settings,
  },
];
