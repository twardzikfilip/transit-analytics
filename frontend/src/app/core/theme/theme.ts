import { DOCUMENT, Injectable, effect, inject, signal } from '@angular/core';

export type ThemeName = 'light' | 'dark';

const STORAGE_KEY = 'theme';

/** Reads the saved theme; light when none is saved or storage is unavailable (private windows, blocked storage). */
export function savedTheme(storage: Pick<Storage, 'getItem'> | null): ThemeName {
  try {
    return storage?.getItem(STORAGE_KEY) === 'dark' ? 'dark' : 'light';
  } catch {
    return 'light';
  }
}

/**
 * The colour theme, light by default: chosen in Settings, remembered in this browser, and applied as
 * `data-theme` on the root element, which the colour tokens in styles.scss follow.
 */
@Injectable({ providedIn: 'root' })
export class Theme {
  private readonly root = inject(DOCUMENT).documentElement;
  private readonly storage = storageOrNull();

  readonly current = signal<ThemeName>(savedTheme(this.storage));

  constructor() {
    effect(() => {
      const theme = this.current();
      this.root.dataset['theme'] = theme;
      try {
        this.storage?.setItem(STORAGE_KEY, theme);
      } catch {
        // Not remembered; the theme still applies until the page is closed.
      }
    });
  }

  set(theme: ThemeName): void {
    this.current.set(theme);
  }
}

function storageOrNull(): Storage | null {
  try {
    return globalThis.localStorage ?? null;
  } catch {
    return null;
  }
}
