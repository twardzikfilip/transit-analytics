import { Component, ElementRef, inject, signal } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { Store } from '@ngrx/store';
import { AuthActions } from '../../store/auth/auth.actions';
import { authFeature } from '../../store/auth/auth.reducer';
import { ApiActivity } from '../../core/api/activity';
import { PageBack } from '../../core/title/page-back';
import { PageTitle } from '../../core/title/page-title';
import { Icon } from '../../shared/icons/icon';
import { ActivityBar } from './activity-bar';
import { Sidebar } from './sidebar';
import { t } from '../../core/i18n/t';

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, Icon, ActivityBar, Sidebar],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
  host: {
    '(document:click)': 'onDocumentClick($event)',
    '(document:keydown.escape)': 'menuOpen.set(false); navOpen.set(false)',
  },
})
export class Shell {
  private readonly store = inject(Store);
  private readonly host = inject(ElementRef<HTMLElement>);

  protected readonly username = this.store.selectSignal(authFeature.selectUsername);
  protected readonly role = this.store.selectSignal(authFeature.selectRole);
  protected readonly menuOpen = signal(false);
  protected readonly t = t;
  /** On a phone, the sidebar is hidden to the left until shown with the button in the header bar. */
  protected readonly navOpen = signal(false);

  /** Whether the app is waiting for the backend. */
  protected readonly busy = inject(ApiActivity).busy;

  /** The page's way back (one brigade, one bus), at the very left of the header. */
  protected readonly back = inject(PageBack).link;

  /** The page's title, from its route, shown in the header bar. */
  protected readonly pageTitle = inject(PageTitle).current;

  protected toggleMenu(event: Event): void {
    event.stopPropagation();
    this.menuOpen.update((open) => !open);
  }

  protected toggleNav(event: Event): void {
    event.stopPropagation();
    this.navOpen.update((open) => !open);
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
