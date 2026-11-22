import de from '../../../i18n/de.json';
import en from '../../../i18n/en.json';
import pl from '../../../i18n/pl.json';
import { activeLang, intlLocale, t, tn } from './t';

type Tree = { [key: string]: string | Tree };

const PLURAL_FORMS = ['zero', 'one', 'two', 'few', 'many', 'other'];

/** Every key of a tree, "a.b.c"; a group of plural forms counts as one key. */
function keys(tree: Tree, prefix = ''): string[] {
  return Object.entries(tree).flatMap(([key, value]) => {
    const path = prefix ? `${prefix}.${key}` : key;
    if (typeof value === 'string') return [path];
    const plural = Object.keys(value).every((k) => PLURAL_FORMS.includes(k));
    return plural ? [path] : keys(value, path);
  });
}

describe('Translations', () => {
  it('has every text in Polish and German that it has in English', () => {
    const english = keys(en as Tree).sort();
    expect(keys(pl as Tree).sort()).toEqual(english);
    expect(keys(de as Tree).sort()).toEqual(english);
  });

  it('keeps each text’s placeholders in every language', () => {
    const placeholders = (text: string) => (text.match(/{{\s*\w+\s*}}/g) ?? []).sort();
    const walk = (a: Tree, b: Tree, path: string): string[] =>
      Object.entries(a).flatMap(([key, value]) => {
        const other = b[key];
        if (typeof value === 'string' && typeof other === 'string')
          return placeholders(value).join() === placeholders(other).join() ? [] : [`${path}${key}`];
        if (typeof value === 'object' && typeof other === 'object')
          return walk(value, other, `${path}${key}.`);
        return [];
      });
    expect(walk(en as Tree, pl as Tree, '')).toEqual([]);
    expect(walk(en as Tree, de as Tree, '')).toEqual([]);
  });

  it('has a plural form for every form the language uses', () => {
    const missing = (tree: Tree, locale: string) => {
      const forms = new Intl.PluralRules(locale).resolvedOptions().pluralCategories;
      const out: string[] = [];
      const walk = (node: Tree, path: string) => {
        for (const [key, value] of Object.entries(node)) {
          if (typeof value === 'string') continue;
          if (Object.keys(value).every((k) => PLURAL_FORMS.includes(k))) {
            for (const f of forms)
              if (f !== 'two' && f !== 'zero' && !(f in value)) out.push(`${path}${key}.${f}`);
          } else walk(value, `${path}${key}.`);
        }
      };
      walk(tree, '');
      return out;
    };
    expect(missing(en as Tree, 'en-GB')).toEqual([]);
    expect(missing(pl as Tree, 'pl-PL')).toEqual([]);
    expect(missing(de as Tree, 'de-DE')).toEqual([]);
  });

  it('shows English, filling in placeholders, before Transloco is set up', () => {
    expect(activeLang()).toBe('en');
    expect(intlLocale()).toBe('en-GB');
    expect(t('titles.route', { value: '103' })).toBe('Route 103');
    expect(t('no.such.key')).toBe('no.such.key');
  });

  it('chooses the plural form by the language’s rules', () => {
    expect(tn('common.days', 1)).toBe('1 day');
    expect(tn('common.days', 5)).toBe('5 days');
  });
});
