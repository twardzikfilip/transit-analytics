import { Routes } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';
import * as cityRailEffects from '../../store/city-rail/city-rail.effects';
import { cityRailFeature } from '../../store/city-rail/city-rail.reducer';
import { CityRail } from './city-rail';
import { Proposal } from './proposal/proposal';
import { ProposalOverview } from './proposal/proposal-overview';
import { ProposalSolutionPage } from './proposal/proposal-solution';

/**
 * City rail, with its store slice, loaded on first visit: the current state, and proposal no. 1 with its two
 * solutions, each on its own page.
 */
export const CITY_RAIL_ROUTES: Routes = [
  {
    path: '',
    providers: [provideState(cityRailFeature), provideEffects(cityRailEffects)],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'current' },
      { path: 'current', title: 'titles.cityRailCurrent', component: CityRail },
      {
        path: 'proposal-1',
        component: Proposal,
        children: [
          { path: '', title: 'titles.cityRailProposal', component: ProposalOverview },
          {
            path: 'radial',
            title: 'titles.cityRailRadial',
            component: ProposalSolutionPage,
            data: { solution: 'radial' },
          },
          {
            path: 'through',
            title: 'titles.cityRailThrough',
            component: ProposalSolutionPage,
            data: { solution: 'through' },
          },
        ],
      },
    ],
  },
];
