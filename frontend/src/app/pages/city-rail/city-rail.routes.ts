import { Routes } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';
import * as cityRailEffects from '../../store/city-rail/city-rail.effects';
import { cityRailFeature } from '../../store/city-rail/city-rail.reducer';
import { CityRail } from './city-rail';

/** City rail, with its store slice, loaded on first visit. */
export const CITY_RAIL_ROUTES: Routes = [
  {
    path: '',
    providers: [provideState(cityRailFeature), provideEffects(cityRailEffects)],
    component: CityRail,
  },
];
