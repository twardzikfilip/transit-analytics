import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Store } from '@ngrx/store';
import { t } from '../../../core/i18n/t';
import { CityRailActions } from '../../../store/city-rail/city-rail.actions';

/**
 * City Rail proposal no. 1: its overview and its two solutions, each a page of its own, reached by the tabs at the
 * top. Loads the proposal (and today's railways, for the maps) once for all three.
 */
@Component({
  selector: 'app-city-rail-proposal',
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  templateUrl: './proposal.html',
  styleUrl: './proposal.scss',
})
export class Proposal {
  protected readonly t = t;

  protected readonly tabs = [
    { path: '/city-rail/proposal-1', label: 'cityRail.proposal.tabs.overview', exact: true },
    { path: '/city-rail/proposal-1/radial', label: 'cityRail.proposal.tabs.radial', exact: false },
    {
      path: '/city-rail/proposal-1/through',
      label: 'cityRail.proposal.tabs.through',
      exact: false,
    },
  ];

  constructor() {
    const store = inject(Store);
    store.dispatch(CityRailActions.load());
    store.dispatch(CityRailActions.loadProposal());
  }
}
