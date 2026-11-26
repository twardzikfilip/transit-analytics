import { GtfsImportActions } from '../../store/gtfs-import/gtfs-import.actions';
import { gtfsImportFeature } from '../../store/gtfs-import/gtfs-import.reducer';
import { VersionsActions } from '../../store/versions/versions.actions';
import { versionsFeature } from '../../store/versions/versions.reducer';
import { APP_VERSION } from '../../core/version';
import { Versions } from '../../core/models/models';
import { TestBed } from '@angular/core/testing';
import { Language } from '../../core/i18n/language';
import { renderPage, signedIn, stateAfter, text } from '../../testing/page-testing';
import '../../core/i18n/i18n.providers'; // registers the languages' locales
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
    unchanged: false,
  };
  const feeds = [
    {
      key: 'bialystok',
      name: null,
      source: 'https://example/gtfs.zip',
      last: { fingerprint: 'f', importedAt: '2026-11-16T10:00:00Z', downloadedBytes: 1 },
    },
    { key: 'rail', name: 'Trains', source: 'https://example/rail.zip', last: null },
    { key: 'new-one', name: 'A new feed', source: 'https://example/new.zip', last: null },
  ];

  it('lets admins import each feed and shows what was loaded', () => {
    const { el, store } = renderPage(Settings, {
      ...signedIn('ADMIN'),
      ...stateAfter(versionsFeature),
      ...stateAfter(
        gtfsImportFeature,
        GtfsImportActions.feedsLoaded({ feeds }),
        GtfsImportActions.importSuccess({ result }),
      ),
    });

    expect(store.dispatch).toHaveBeenCalledWith(GtfsImportActions.loadFeeds());
    expect([...el.querySelectorAll('.feeds tbody tr')].map(cells)).toEqual([
      // The time is shown in the browser's zone.
      [
        'Białystok city buses (BKM)',
        expect.stringMatching(/^Nov 16, 2026, \d+:00:00 AM$/),
        'Import',
      ],
      ['Polregio and PKP Intercity trains', 'never', 'Import'],
      ['A new feed', 'never', 'Import'],
    ]);
    (el.querySelectorAll('.feeds button')[1] as HTMLButtonElement).click();
    expect(store.dispatch).toHaveBeenCalledWith(GtfsImportActions.importFeed({ feed: 'rail' }));
    expect(text(el.querySelector('.result .feed-name'))).toBe('Białystok city buses (BKM)');
    expect([...el.querySelectorAll('.result tr')].map(cells)).toEqual([
      ['Routes', '55'],
      ['Stops', '1,083'],
    ]);
    expect(text(el.querySelector('.result'))).toContain('31.4 s');
    expect(text(el.querySelector('.result'))).toContain('Skipped 4 rows of stop_time');
    expect(text(el.querySelector('.result'))).toContain('Not loaded: feed_info.txt');
  });

  it('says when a feed was the same as its last import', () => {
    const el = renderPage(Settings, {
      ...signedIn('ADMIN'),
      ...stateAfter(versionsFeature),
      ...stateAfter(
        gtfsImportFeature,
        GtfsImportActions.feedsLoaded({ feeds }),
        GtfsImportActions.importSuccess({
          result: {
            ...result,
            feed: 'rail',
            rows: {},
            skipped: {},
            ignoredFiles: [],
            unchanged: true,
          },
        }),
      ),
    }).el;
    expect(text(el.querySelector('.result'))).toMatch(
      /^Polregio and PKP Intercity trains: the feed is the same as imported Nov 16, 2026, \d+:00:00 AM, so nothing was imported\.$/,
    );
    expect(el.querySelector('.result table')).toBeNull();
  });

  it('shows a running import, with the other feeds waiting', () => {
    const running = renderPage(Settings, {
      ...signedIn('ADMIN'),
      ...stateAfter(versionsFeature),
      ...stateAfter(
        gtfsImportFeature,
        GtfsImportActions.feedsLoaded({ feeds }),
        GtfsImportActions.importFeed({ feed: 'rail' }),
      ),
    }).el;
    const buttons = [...running.querySelectorAll('.feeds button')] as HTMLButtonElement[];
    expect(buttons.map((b) => b.disabled)).toEqual([true, true, true]);
    expect(buttons.map(text)).toEqual(['Import', 'Importing…', 'Import']);
  });

  it('shows a skeleton until the feeds are loaded', () => {
    const loading = renderPage(Settings, {
      ...signedIn('ADMIN'),
      ...stateAfter(versionsFeature),
      ...stateAfter(gtfsImportFeature),
    }).el;
    expect(loading.querySelector('.gtfs app-skeleton')).not.toBeNull();
  });

  it('leaves a failed import to the toast: nothing of it on the page', () => {
    const failed = renderPage(Settings, {
      ...signedIn('ADMIN'),
      ...stateAfter(versionsFeature),
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
      ...stateAfter(versionsFeature),
      ...stateAfter(gtfsImportFeature),
    }).el;
    expect(guest.querySelector('.gtfs')).toBeNull();
    expect([...guest.querySelectorAll('div.card dd')].map(text)).toEqual([
      'ann',
      'GUEST',
      'Light Dark',
      'English▾',
    ]);
  });

  it('shows the versions, and says so when the server has another than this page', () => {
    const shown = (versions: Versions) =>
      [
        ...renderPage(Settings, {
          ...signedIn('GUEST'),
          ...stateAfter(gtfsImportFeature),
          ...stateAfter(versionsFeature, VersionsActions.loaded({ versions })),
        }).el.querySelectorAll('.versions dd'),
      ].map(text);
    const app = expect.stringMatching(new RegExp(`^${APP_VERSION} · released \\w+ \\d+, \\d{4}$`));

    expect(
      shown({
        backend: { version: APP_VERSION, released: '2026-11-24' },
        recorder: { version: '4.5.6', released: '2026-11-20' },
      }),
    ).toEqual([app, '4.5.6 · released Nov 20, 2026']);
    TestBed.resetTestingModule();
    expect(
      shown({ backend: { version: '99.0.0', released: '2026-12-01' }, recorder: null }),
    ).toEqual([
      app,
      '99.0.0 · reload the page to get this version',
      "unknown (it doesn't answer, or is older than versions)",
    ]);
  });

  it('switches the language in a dropdown of flags and native names, at once and remembered', async () => {
    localStorage.removeItem('language');
    const { el, render } = renderPage(Settings, {
      ...signedIn('GUEST'),
      ...stateAfter(versionsFeature),
      ...stateAfter(gtfsImportFeature),
    });
    const button = el.querySelector('app-language-picker button.current') as HTMLButtonElement;
    expect(button.getAttribute('aria-haspopup')).toBe('listbox');
    button.click();
    render();
    const options = [...el.querySelectorAll('[role=option]')] as HTMLElement[];
    expect(options.map(text)).toEqual(['English✓', 'Polski', 'Deutsch', 'Français']);
    expect(options.map((o) => o.querySelector('app-flag svg') !== null)).toEqual([
      true,
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
      ...stateAfter(versionsFeature),
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
