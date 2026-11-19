import { Routes } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';
import * as brigadesEffects from '../../store/brigades/brigades.effects';
import { brigadesFeature } from '../../store/brigades/brigades.reducer';
import { BrigadesPage } from './brigades';

/** Brigades, with its store slice, loaded on first visit. */
export const BRIGADES_ROUTES: Routes = [
  {
    path: '',
    providers: [provideState(brigadesFeature), provideEffects(brigadesEffects)],
    component: BrigadesPage,
  },
];
