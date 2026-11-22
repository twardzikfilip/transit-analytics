import { resetLanguage } from './app/core/i18n/t';

// Every test starts in English, whatever language an earlier test chose.
beforeEach(() => resetLanguage());
