import { Phrase } from '../../core/models/models';
import { Params, t } from '../../core/i18n/t';
import { num } from '../format/number';

/** A note the backend sent, in the language shown: its text under `tripNotes`, numbers as the language writes them. */
export function phraseText(phrase: Phrase): string {
  const params: Params = {};
  for (const [name, value] of Object.entries(phrase.params ?? {})) {
    params[name] = typeof value === 'number' ? num(value) : value;
  }
  return t(`tripNotes.${phrase.key}`, params);
}

/** Phrases as one text, separated by dots; empty for none. */
export function phrasesText(phrases: Phrase[] | null | undefined): string {
  return (phrases ?? []).map(phraseText).join(' · ');
}
