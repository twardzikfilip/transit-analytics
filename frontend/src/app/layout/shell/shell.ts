import { Component, ElementRef, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Store } from '@ngrx/store';
import { AuthActions } from '../../store/auth/auth.actions';
import { authFeature } from '../../store/auth/auth.reducer';
import { PageTitle } from '../../core/title/page-title';
import { Icon } from '../../shared/icons/icon';
import { IconName } from '../../shared/icons/icons';

interface NavItem {
  path: string;
  label: string;
  icon: IconName;
}

interface NavGroup {
  /** Its heading; null: no heading (Home, on its own at the top). */
  label: string | null;
  items: NavItem[];
}

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Icon],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
  host: {
    '(document:click)': 'onDocumentClick($event)',
    '(document:keydown.escape)': 'menuOpen.set(false)',
  },
})
export class Shell {
  private readonly store = inject(Store);
  private readonly host = inject(ElementRef<HTMLElement>);

  protected readonly username = this.store.selectSignal(authFeature.selectUsername);
  protected readonly role = this.store.selectSignal(authFeature.selectRole);
  protected readonly menuOpen = signal(false);

  /** The page's title, from its route, shown in the header bar. */
  protected readonly pageTitle = inject(PageTitle).current;

  /** The sidebar: Home on its own, then day-to-day operation, then the network and its timetable. */
  protected readonly navGroups: NavGroup[] = [
    { label: null, items: [{ path: '/home', label: 'Home', icon: 'home' }] },
    {
      label: 'Operation',
      items: [
        { path: '/brigades', label: 'Brigades', icon: 'brigades' },
        { path: '/fleet', label: 'Fleet', icon: 'fleet' },
        { path: '/trip-anomalies', label: 'Abnormal trips', icon: 'tripAnomalies' },
      ],
    },
    {
      label: 'Network',
      items: [
        { path: '/hotspots', label: 'Stop hotspots', icon: 'hotspots' },
        { path: '/bus-lanes', label: 'Bus lanes', icon: 'busLanes' },
        { path: '/delay-generation', label: 'Delay generation', icon: 'delayGeneration' },
        { path: '/routes', label: 'Routes', icon: 'routes' },
        { path: '/corridors', label: 'Important corridors', icon: 'corridors' },
        { path: '/network-coverage', label: 'Network coverage', icon: 'networkCoverage' },
        { path: '/stops', label: 'Stops', icon: 'stops' },
        { path: '/trips', label: 'Trips', icon: 'trips' },
        { path: '/stop-times', label: 'Stop times', icon: 'stopTimes' },
        { path: '/sources', label: 'Sources', icon: 'sources' },
      ],
    },
  ];

  protected toggleMenu(event: Event): void {
    event.stopPropagation();
    this.menuOpen.update((open) => !open);
  }

  protected logout(): void {
    this.menuOpen.set(false);
    this.store.dispatch(AuthActions.logout());
  }

  protected onDocumentClick(event: Event): void {
    const menu = this.host.nativeElement.querySelector('.menu');
    if (menu && !menu.contains(event.target as Node)) {
      this.menuOpen.set(false);
    }
  }
}
