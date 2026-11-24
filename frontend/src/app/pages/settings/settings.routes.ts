import { Routes } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';
import * as gtfsImportEffects from '../../store/gtfs-import/gtfs-import.effects';
import { gtfsImportFeature } from '../../store/gtfs-import/gtfs-import.reducer';
import * as versionsEffects from '../../store/versions/versions.effects';
import { versionsFeature } from '../../store/versions/versions.reducer';
import { Settings } from './settings';

/** Settings, with the GTFS import and versions slices it uses, loaded on first visit. */
export const SETTINGS_ROUTES: Routes = [
  {
    path: '',
    providers: [
      provideState(gtfsImportFeature),
      provideState(versionsFeature),
      provideEffects(gtfsImportEffects, versionsEffects),
    ],
    component: Settings,
  },
];
