import { Routes } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';
import * as brigadesEffects from '../../store/brigades/brigades.effects';
import { brigadesFeature } from '../../store/brigades/brigades.reducer';
import * as tripRunsEffects from '../../store/trip-runs/trip-runs.effects';
import { tripRunsFeature } from '../../store/trip-runs/trip-runs.reducer';
import { BrigadePage } from './brigade';
import { BrigadesPage } from './brigades';
import { openFormerQueryLink } from '../../shared/url-query';

/** Brigades (every brigade) and one brigade's page (with the Trips page's list), sharing the store slices loaded on first visit. */
export const BRIGADES_ROUTES: Routes = [
  {
    path: '',
    providers: [
      provideState(brigadesFeature),
      provideState(tripRunsFeature),
      provideEffects(brigadesEffects, tripRunsEffects),
    ],
    children: [
      {
        path: '',
        component: BrigadesPage,
        canActivate: [openFormerQueryLink('brigade', ['/brigades'])],
      },
      {
        path: ':brigade',
        title: (route) => `titles.brigade:${route.paramMap.get('brigade')}`,
        component: BrigadePage,
      },
    ],
  },
];
