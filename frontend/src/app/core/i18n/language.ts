import { DOCUMENT, Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Translation, TranslocoLoader, TranslocoService } from '@jsverse/transloco';
import { Observable, firstValueFrom, of } from 'rxjs';
import en from '../../../i18n/en.json';
import { LANGS, Lang, useLanguage } from './t';

const STORAGE_KEY = 'language';

/** A language to choose in Settings: its name in its own language, and its flag (shared/i18n/flag). */
export interface LanguageOption {
  code: Lang;
  name: string;
}

export const LANGUAGE_OPTIONS: LanguageOption[] = [
  { code: 'en', name: 'English' },
  { code: 'pl', name: 'Polski' },
  { code: 'de', name: 'Deutsch' },
  { code: 'fr', name: 'Français' },
];

/** The saved language; English when none is saved or storage is unavailable. */
export function savedLanguage(storage: Pick<Storage, 'getItem'> | null): Lang {
  try {
    const saved = storage?.getItem(STORAGE_KEY);
    return LANGS.includes(saved as Lang) ? (saved as Lang) : 'en';
  } catch {
    return 'en';
  }
}

/** English is part of the app (no wait, and the fallback); Polish and German come from /i18n/ when chosen. */
@Injectable({ providedIn: 'root' })
export class I18nLoader implements TranslocoLoader {
  private readonly http = inject(HttpClient);

  getTranslation(lang: string): Observable<Translation> {
    return lang === 'en' ? of(en) : this.http.get<Translation>(`/i18n/${lang}.json`);
  }
}

/**
 * The language of the app, English by default: chosen in Settings, remembered in this browser, set on <html lang>.
 * A change applies at once (Transloco), once the language's translations are loaded.
 */
@Injectable({ providedIn: 'root' })
export class Language {
  private readonly transloco = inject(TranslocoService);
  private readonly root = inject(DOCUMENT).documentElement;
  private readonly storage = storageOrNull();

  readonly current = signal<Lang>(savedLanguage(this.storage));
  private loaded: Promise<void>;

  constructor() {
    this.loaded = this.apply(this.current());
  }

  /** Resolves when the current language's translations are in (the app waits for it before its first page). */
  ready(): Promise<void> {
    return this.loaded;
  }

  set(lang: Lang): void {
    this.current.set(lang);
    try {
      this.storage?.setItem(STORAGE_KEY, lang);
    } catch {
      // Not remembered; it still applies until the page is closed.
    }
    this.loaded = this.apply(lang);
  }

  private apply(lang: Lang): Promise<void> {
    return firstValueFrom(this.transloco.load(lang)).then(
      () => {
        // A later choice wins over a slower load.
        if (this.current() !== lang) return;
        this.transloco.setActiveLang(lang);
        useLanguage(this.transloco, lang);
        this.root.lang = lang;
      },
      () => {
        // The language's file didn't load (offline, a failed deploy): stay in English rather than fail to start.
        if (this.current() !== lang || lang === 'en') return;
        this.current.set('en');
        return this.apply('en');
      },
    );
  }
}

function storageOrNull(): Storage | null {
  try {
    return globalThis.localStorage ?? null;
  } catch {
    return null;
  }
}
