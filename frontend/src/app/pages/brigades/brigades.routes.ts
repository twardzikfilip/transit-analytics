import { Routes } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';
import * as brigadesEffects from '../../store/brigades/brigades.effects';
import { brigadesFeature } from '../../store/brigades/brigades.reducer';
import { BrigadePage } from './brigade';
import { BrigadesPage } from './brigades';
import { openFormerQueryLink } from '../../shared/url-query';

/** Brigades (every brigade) and one brigade's page, sharing the store slice loaded on first visit. */
export const BRIGADES_ROUTES: Routes = [
  {
    path: '',
    providers: [provideState(brigadesFeature), provideEffects(brigadesEffects)],
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
