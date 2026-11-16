import { Component, ElementRef, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Store } from '@ngrx/store';
import { AuthActions } from '../../store/auth/auth.actions';
import { authFeature } from '../../store/auth/auth.reducer';

interface NavItem {
  path: string;
  label: string;
  icon: string;
}

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
  host: { '(document:click)': 'onDocumentClick($event)', '(document:keydown.escape)': 'menuOpen.set(false)' },
})
export class Shell {
  private readonly store = inject(Store);
  private readonly host = inject(ElementRef<HTMLElement>);

  protected readonly username = this.store.selectSignal(authFeature.selectUsername);
  protected readonly role = this.store.selectSignal(authFeature.selectRole);
  protected readonly menuOpen = signal(false);

  protected readonly navItems: NavItem[] = [
    { path: '/home', label: 'Home', icon: '⌂' },
    { path: '/vehicles', label: 'Vehicles', icon: '🚌' },
    { path: '/live-map', label: 'Live map', icon: '🗺' },
    { path: '/positions', label: 'Position history', icon: '📈' },
    { path: '/delays', label: 'Delays', icon: '⏱' },
    { path: '/routes', label: 'Routes', icon: '〰' },
    { path: '/lines', label: 'Line statistics', icon: '📊' },
    { path: '/stops', label: 'Stops', icon: '🚏' },
    { path: '/trips', label: 'Trips', icon: '⇄' },
    { path: '/stop-times', label: 'Stop times', icon: '🕑' },
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
