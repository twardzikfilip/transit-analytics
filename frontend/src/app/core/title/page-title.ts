import { Injectable, inject, signal } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { RouterStateSnapshot, TitleStrategy } from '@angular/router';

export const APP_NAME = 'Transit Analytics';

/**
 * Page titles come from the routes' `title`: shown in the header bar (see Shell) and as the browser tab's title,
 * "Fleet · Transit Analytics".
 */
@Injectable({ providedIn: 'root' })
export class PageTitle extends TitleStrategy {
  private readonly title = inject(Title);
  /** The page's title, '' when its route has none. */
  readonly current = signal('');

  override updateTitle(snapshot: RouterStateSnapshot): void {
    const page = this.buildTitle(snapshot) ?? '';
    this.current.set(page);
    this.title.setTitle(page ? `${page} · ${APP_NAME}` : APP_NAME);
  }
}
