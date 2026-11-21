import { GtfsImportActions } from '../../store/gtfs-import/gtfs-import.actions';
import { gtfsImportFeature } from '../../store/gtfs-import/gtfs-import.reducer';
import { renderPage, signedIn, stateAfter, text } from '../../testing/page-testing';

const cells = (row: Element) => [...row.children].map(text);
import { Settings } from './settings';

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
    expect([...guest.querySelectorAll('dd')].map(text)).toEqual(['ann', 'GUEST', 'Light Dark']);
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
