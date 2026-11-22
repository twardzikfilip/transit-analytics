import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { RouterStateSnapshot, TitleStrategy } from '@angular/router';
import { t } from '../i18n/t';

export const APP_NAME = 'Transit Analytics';

/**
 * Page titles come from the routes' `title`, a translation key ('titles.routes'), or a key and a value after a colon
 * ('titles.route:103' → "Route 103", the value as {{value}}). Shown in the header bar (see Shell) and as the browser
 * tab's title, "Fleet Overview · Transit Analytics", in the language shown.
 */
@Injectable({ providedIn: 'root' })
export class PageTitle extends TitleStrategy {
  private readonly title = inject(Title);
  /** The route's title as given: a key, maybe with a value. */
  readonly route = signal('');
  /** The page's title in the language shown, '' when its route has none. */
  readonly current = computed(() => pageTitle(this.route()));

  constructor() {
    super();
    effect(() => {
      const page = this.current();
      this.title.setTitle(page ? `${page} · ${APP_NAME}` : APP_NAME);
    });
  }

  override updateTitle(snapshot: RouterStateSnapshot): void {
    this.route.set(this.buildTitle(snapshot) ?? '');
  }
}

/** "titles.route:103" → "Route 103"; a title that isn't a key is shown as it is. */
export function pageTitle(title: string): string {
  if (!title) return '';
  const at = title.indexOf(':');
  const key = at < 0 ? title : title.slice(0, at);
  return key.startsWith('titles.') ? t(key, { value: at < 0 ? '' : title.slice(at + 1) }) : title;
}
