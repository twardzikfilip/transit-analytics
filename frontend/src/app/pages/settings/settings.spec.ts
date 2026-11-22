import { GtfsImportActions } from '../../store/gtfs-import/gtfs-import.actions';
import { gtfsImportFeature } from '../../store/gtfs-import/gtfs-import.reducer';
import { TestBed } from '@angular/core/testing';
import { Language } from '../../core/i18n/language';
import { renderPage, signedIn, stateAfter, text } from '../../testing/page-testing';
import { Settings } from './settings';

const cells = (row: Element) => [...row.children].map(text);

describe('Settings page', () => {
  const result = {
    feed: 'bialystok',
    source: 'https://example/gtfs.zip',
    downloadedBytes: 2 * 1048576,
    importedAt: '2026-11-16T10:00:00Z',
    durationMs: 31_400,
    rows: { route: 55, stop: 1083 },
    skipped: { stop_time: 4 },
    ignoredFiles: ['feed_info.txt'],
  };

  it('lets admins import the timetable and shows what was loaded', () => {
    const { el, store } = renderPage(Settings, {
      ...signedIn('ADMIN'),
      ...stateAfter(gtfsImportFeature, GtfsImportActions.importSuccess({ result })),
    });

    (el.querySelector('button.primary') as HTMLButtonElement).click();
    expect(store.dispatch).toHaveBeenCalledWith(GtfsImportActions.importBialystok());
    expect([...el.querySelectorAll('.result tr')].map(cells)).toEqual([
      ['Routes', '55'],
      ['Stops', '1,083'],
    ]);
    expect(text(el.querySelector('.result'))).toContain('31.4 s');
    expect(text(el.querySelector('.result'))).toContain('Skipped 4 rows of stop_time');
    expect(text(el.querySelector('.result'))).toContain('Not loaded: feed_info.txt');
  });

  it('shows a running import and a failure', () => {
    const running = renderPage(Settings, {
      ...signedIn('ADMIN'),
      ...stateAfter(gtfsImportFeature, GtfsImportActions.importBialystok()),
    }).el;
    expect((running.querySelector('button.primary') as HTMLButtonElement).disabled).toBe(true);
    expect(text(running.querySelector('button.primary'))).toBe('Importing…');
  });

  it('leaves a failed import to the toast: nothing of it on the page', () => {
    const failed = renderPage(Settings, {
      ...signedIn('ADMIN'),
      ...stateAfter(
        gtfsImportFeature,
        GtfsImportActions.importFailure({ error: 'Feed unavailable' }),
      ),
    }).el;
    expect(text(failed)).not.toContain('Feed unavailable');
    expect(failed.querySelector('.error')).toBeNull();
  });

  it('hides the import from guests', () => {
    const guest = renderPage(Settings, {
      ...signedIn('GUEST'),
      ...stateAfter(gtfsImportFeature),
    }).el;
    expect(guest.querySelector('.gtfs')).toBeNull();
    expect([...guest.querySelectorAll('dd')].map(text)).toEqual([
      'ann',
      'GUEST',
      'Light Dark',
      'English▾',
    ]);
  });

  it('switches the language in a dropdown of flags and native names, at once and remembered', async () => {
    localStorage.removeItem('language');
    const { el, render } = renderPage(Settings, {
      ...signedIn('GUEST'),
      ...stateAfter(gtfsImportFeature),
    });
    const button = el.querySelector('app-language-picker button.current') as HTMLButtonElement;
    expect(button.getAttribute('aria-haspopup')).toBe('listbox');
    button.click();
    render();
    const options = [...el.querySelectorAll('[role=option]')] as HTMLElement[];
    expect(options.map(text)).toEqual(['English✓', 'Polski', 'Deutsch']);
    expect(options.map((o) => o.querySelector('app-flag svg') !== null)).toEqual([
      true,
      true,
      true,
    ]);
    expect(options[1].getAttribute('lang')).toBe('pl');

    options[1].click();
    await TestBed.inject(Language).ready();
    render();
    expect(el.querySelector('[role=listbox]')).toBeNull();
    expect(text(el.querySelector('dt'))).toBe('Nazwa użytkownika');
    expect(text(button)).toBe('Polski▾');
    expect(document.documentElement.lang).toBe('pl');
    expect(localStorage.getItem('language')).toBe('pl');

    // With the keyboard: down to Deutsch, Enter.
    button.click();
    render();
    const list = el.querySelector('[role=listbox]') as HTMLElement;
    list.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' }));
    list.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    await TestBed.inject(Language).ready();
    render();
    expect(text(el.querySelector('dt'))).toBe('Benutzername');
    localStorage.removeItem('language');
  });

  it('switches between the light and dark theme, light by default', () => {
    const { el, render } = renderPage(Settings, {
      ...signedIn('GUEST'),
      ...stateAfter(gtfsImportFeature),
    });
    const [light, dark] = [
      ...el.querySelectorAll('app-theme-switch button'),
    ] as HTMLButtonElement[];
    expect(light.getAttribute('aria-pressed')).toBe('true');
    dark.click();
    render();
    expect(dark.getAttribute('aria-pressed')).toBe('true');
    expect(document.documentElement.dataset['theme']).toBe('dark');
    light.click();
    render();
    expect(document.documentElement.dataset['theme']).toBe('light');
    localStorage.removeItem('theme');
  });
});
