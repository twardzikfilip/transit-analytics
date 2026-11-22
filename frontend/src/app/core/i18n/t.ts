import { signal } from '@angular/core';
import type { TranslocoService } from '@jsverse/transloco';
import en from '../../../i18n/en.json';

/** The languages of the app: English (the default), Polish and German. */
export type Lang = 'en' | 'pl' | 'de';
export const LANGS: Lang[] = ['en', 'pl', 'de'];

/** Each language's locale for Intl (dates, plurals) and for Angular's date and number formats. */
/** Values filled into a text's {{placeholders}}. */
export type Params = Record<string, unknown>;

const INTL_LOCALE: Record<Lang, string> = { en: 'en-GB', pl: 'pl-PL', de: 'de-DE' };
const ANGULAR_LOCALE: Record<Lang, string> = { en: 'en-US', pl: 'pl', de: 'de' };

/**
 * The language shown, set once its translations are loaded (see Language). Everything below reads it, so templates
 * and computed values that translate or format follow a change of language at once.
 */
const active = signal<Lang>('en');
let transloco: TranslocoService | null = null;

/** Back to English without Transloco (before each unit test, so no test sees another's language). */
export function resetLanguage(): void {
  transloco = null;
  active.set('en');
}

/** Called by Language when a language's translations are in. */
export function useLanguage(service: TranslocoService, lang: Lang): void {
  transloco = service;
  active.set(lang);
}

export function activeLang(): Lang {
  return active();
}

/** The Intl locale of the language shown, e.g. 'pl-PL'. */
export function intlLocale(): string {
  return INTL_LOCALE[active()];
}

/** Angular's locale id of the language shown, for formatDate/formatNumber. */
export function angularLocale(): string {
  return ANGULAR_LOCALE[active()];
}

/**
 * The text for `key` (e.g. 'fleet.inUse') in the language shown, with {{params}} filled in (Transloco). Before
 * Transloco is set up (unit tests) the English text is used.
 */
export function t(key: string, params?: Params): string {
  const lang = active();
  if (transloco) return transloco.translate(key, params, lang);
  return fill(lookup(en, key) ?? key, params);
}

/**
 * A count with its noun in the right form for the language: `key.one`, `key.few`, `key.many` or `key.other` (Polish
 * has all of them, English and German one and other), with the count as {{n}}, or grouped as {{count}} ("1,083").
 */
export function tn(key: string, n: number, params?: Params): string {
  const form = new Intl.PluralRules(intlLocale()).select(n);
  const values = { n, count: n.toLocaleString(intlLocale()), ...params };
  const text = t(`${key}.${form}`, values);
  return text === `${key}.${form}` ? t(`${key}.other`, values) : text;
}

function lookup(tree: unknown, key: string): string | null {
  const value = key
    .split('.')
    .reduce<unknown>((node, part) => (node as Record<string, unknown> | undefined)?.[part], tree);
  return typeof value === 'string' ? value : null;
}

function fill(text: string, params?: Params): string {
  return params
    ? text.replace(/{{\s*(\w+)\s*}}/g, (all, name: string) =>
        name in params ? String(params[name]) : all,
      )
    : text;
}
