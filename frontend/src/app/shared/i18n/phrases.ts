import en from '../../../i18n/en.json';
import { Phrase } from '../../core/models/models';
import { Params, t, tn } from '../../core/i18n/t';
import { num } from '../format/number';

/** The keys of `tripNotes` with forms for counts ("1 stop", "3 stops"); their count is the value `n`. */
const COUNTED = new Set(
  Object.entries(en.tripNotes)
    .filter(([, text]) => typeof text === 'object')
    .map(([key]) => key),
);

function isPhrase(value: unknown): value is Phrase {
  return typeof value === 'object' && value !== null && 'key' in value;
}

/**
 * A phrase the backend sent (a trip note or a step of a story) in the language shown: its text under `tripNotes`,
 * with its values filled in. A value that is a phrase is written first; a number follows the language ("4,2").
 */
export function phraseText(phrase: Phrase): string {
  const params: Params = {};
  for (const [name, value] of Object.entries(phrase.params ?? {})) {
    params[name] = isPhrase(value)
      ? phraseText(value)
      : typeof value === 'number'
        ? num(value)
        : value;
  }
  const key = `tripNotes.${phrase.key}`;
  const n = phrase.params?.['n'];
  return COUNTED.has(phrase.key) && typeof n === 'number' ? tn(key, n, params) : t(key, params);
}

/** Phrases as one text, sentence after sentence; empty for none. */
export function phrasesText(phrases: Phrase[] | null | undefined): string {
  return (phrases ?? []).map(phraseText).join(' ');
}
