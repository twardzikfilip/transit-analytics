import { KeyValuePipe, formatDate } from '@angular/common';
import { Component, inject } from '@angular/core';
import { Store } from '@ngrx/store';
import { HasRoleDirective } from '../../core/auth/has-role.directive';
import { authFeature } from '../../store/auth/auth.reducer';
import { GtfsImportActions } from '../../store/gtfs-import/gtfs-import.actions';
import { gtfsImportFeature } from '../../store/gtfs-import/gtfs-import.reducer';
import { VersionsActions } from '../../store/versions/versions.actions';
import { versionsFeature } from '../../store/versions/versions.reducer';
import { APP_RELEASED, APP_VERSION } from '../../core/version';
import { Skeleton } from '../../shared/skeleton/skeleton';
import { ThemeSwitch } from './theme-switch';
import { angularLocale, t } from '../../core/i18n/t';
import { LocalDatePipe, LocalNumberPipe } from '../../shared/i18n/locale-pipes';
import { LanguagePicker } from './language-picker';
import { GtfsFeed } from '../../core/models/models';

@Component({
  selector: 'app-settings',
  imports: [
    HasRoleDirective,
    LocalNumberPipe,
    LocalDatePipe,
    KeyValuePipe,
    ThemeSwitch,
    LanguagePicker,
    Skeleton,
  ],
  template: `
    <div class="card">
      <dl>
        <dt>{{ t('settings.username') }}</dt>
        <dd>{{ username() }}</dd>
        <dt>{{ t('settings.role') }}</dt>
        <dd>{{ role() }}</dd>
        <dt>{{ t('settings.theme') }}</dt>
        <dd><app-theme-switch /></dd>
        <dt id="language-label">{{ t('settings.language') }}</dt>
        <dd><app-language-picker /></dd>
      </dl>
      <p class="muted">{{ t('settings.remembered') }}</p>
    </div>

    <section class="card versions">
      <h2>{{ t('settings.versions') }}</h2>
      <dl>
        <dt>{{ t('settings.versionApp') }}</dt>
        <dd>{{ appVersion }} · {{ released(appReleased) }}</dd>
        @if (versions(); as v) {
          @if (v.backend.version !== appVersion) {
            <dt>{{ t('settings.versionServer') }}</dt>
            <dd>{{ v.backend.version }} · {{ t('settings.versionReload') }}</dd>
          }
          <dt>{{ t('settings.versionRecorder') }}</dt>
          <dd>
            @if (v.recorder; as r) {
              {{ r.version }}
              @if (r.released) {
                · {{ released(r.released) }}
              }
            } @else {
              {{ t('settings.versionUnknown') }}
            }
          </dd>
        } @else if (loadingVersions()) {
          <dt>{{ t('settings.versionRecorder') }}</dt>
          <dd><app-skeleton kind="lines" [count]="1" /></dd>
        }
      </dl>
    </section>

    <section *appHasRole="'ADMIN'" class="card gtfs">
      <h2>{{ t('settings.gtfsTitle') }}</h2>
      <p class="muted">{{ t('settings.gtfsIntro') }}</p>
      @if (feeds(); as list) {
        <table class="data-table feeds">
          <thead>
            <tr>
              <th>{{ t('settings.feed') }}</th>
              <th>{{ t('settings.lastImport') }}</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            @for (f of list; track f.key) {
              <tr>
                <td>{{ feedName(f) }}</td>
                <td>
                  @if (f.last; as last) {
                    {{ last.importedAt | date: 'medium' }}
                  } @else {
                    <span class="muted">{{ t('settings.never') }}</span>
                  }
                </td>
                <td>
                  <button
                    type="button"
                    class="primary"
                    [disabled]="running() !== null"
                    (click)="importFeed(f.key)"
                  >
                    {{ t(running() === f.key ? 'settings.importing' : 'settings.import') }}
                  </button>
                </td>
              </tr>
            }
          </tbody>
        </table>
      } @else {
        <app-skeleton kind="lines" [count]="5" />
      }

      @if (result(); as r) {
        <div class="result" role="status">
          @if (r.unchanged) {
            <p>
              {{
                t('settings.unchanged', {
                  feed: feedNameOf(r.feed),
                  when: r.importedAt | date: 'medium',
                })
              }}
            </p>
          } @else {
            <p class="feed-name">{{ feedNameOf(r.feed) }}</p>
            <p>
              {{
                t('settings.imported', {
                  when: r.importedAt | date: 'medium',
                  seconds: r.durationMs / 1000 | number: '1.0-1',
                  mb: r.downloadedBytes / 1048576 | number: '1.1-1',
                })
              }}
            </p>
            <table class="data-table">
              <tbody>
                @for (row of r.rows | keyvalue: keepOrder; track row.key) {
                  <tr>
                    <th>{{ label(row.key) }}</th>
                    <td class="num">{{ row.value | number }}</td>
                  </tr>
                }
              </tbody>
            </table>
            @for (s of r.skipped | keyvalue; track s.key) {
              <p class="muted">
                {{ t('settings.skipped', { count: s.value | number, table: s.key }) }}
              </p>
            }
            @if (r.ignoredFiles.length) {
              <p class="muted">
                {{ t('settings.notLoaded', { files: r.ignoredFiles.join(', ') }) }}
              </p>
            }
          }
        </div>
      }
    </section>
  `,
  styles: `
    dl {
      display: grid;
      grid-template-columns: max-content 1fr;
      gap: 0.5rem 1.5rem;
      align-items: center;
      margin: 0 0 1rem;
    }
    dt {
      color: var(--muted);
    }
    dd {
      margin: 0;
    }
    .versions dl {
      margin-bottom: 0;
    }
    .versions,
    .gtfs {
      margin-top: 1rem;
      h2 {
        font-size: 1.1rem;
        margin: 0 0 0.5rem;
      }
    }
    .feeds {
      max-width: 48rem;
      td:last-child {
        text-align: right;
        white-space: nowrap;
      }
    }
    .feed-name {
      font-weight: 600;
    }
    .result {
      margin-top: 1rem;
      max-width: 28rem;
      th {
        color: var(--fg);
        font-weight: normal;
        font-size: inherit;
      }
      td {
        text-align: right;
      }
    }
  `,
})
export class Settings {
  private readonly store = inject(Store);
  protected readonly t = t;
  protected readonly username = this.store.selectSignal(authFeature.selectUsername);
  protected readonly role = this.store.selectSignal(authFeature.selectRole);

  protected readonly appVersion = APP_VERSION;
  protected readonly appReleased = APP_RELEASED;
  protected readonly versions = this.store.selectSignal(versionsFeature.selectVersions);
  protected readonly loadingVersions = this.store.selectSignal(versionsFeature.selectLoading);

  protected readonly feeds = this.store.selectSignal(gtfsImportFeature.selectFeeds);
  protected readonly running = this.store.selectSignal(gtfsImportFeature.selectRunning);
  protected readonly result = this.store.selectSignal(gtfsImportFeature.selectResult);

  constructor() {
    this.store.dispatch(VersionsActions.load());
    // Only admins see the import.
    if (this.role() === 'ADMIN') {
      this.store.dispatch(GtfsImportActions.loadFeeds());
    }
  }

  /** Keep the server's table order instead of sorting by name. */
  protected readonly keepOrder = () => 0;

  /** "released Nov 24, 2026", in the language shown. */
  protected released(day: string): string {
    return t('settings.released', { date: formatDate(day, 'mediumDate', angularLocale()) });
  }

  protected importFeed(feed: string): void {
    this.store.dispatch(GtfsImportActions.importFeed({ feed }));
  }

  /** A feed's name in the language shown; the server's name, or its key, for a feed the app doesn't know. */
  protected feedName(feed: GtfsFeed): string {
    const name = t(`settings.feeds.${feed.key}`);
    return name === `settings.feeds.${feed.key}` ? (feed.name ?? feed.key) : name;
  }

  protected feedNameOf(key: string): string {
    const feed = this.feeds()?.find((f) => f.key === key);
    return this.feedName(feed ?? { key, name: null, source: '', last: null });
  }

  /** A table's name, as shown to people; as the server names it when not known. */
  protected label(table: string): string {
    const name = t(`settings.tables.${table}`);
    return name === `settings.tables.${table}` ? table : name;
  }
}
