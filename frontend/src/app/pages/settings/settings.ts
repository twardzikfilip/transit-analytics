import { KeyValuePipe } from '@angular/common';
import { Component, inject } from '@angular/core';
import { Store } from '@ngrx/store';
import { HasRoleDirective } from '../../core/auth/has-role.directive';
import { authFeature } from '../../store/auth/auth.reducer';
import { GtfsImportActions } from '../../store/gtfs-import/gtfs-import.actions';
import { gtfsImportFeature } from '../../store/gtfs-import/gtfs-import.reducer';
import { ThemeSwitch } from './theme-switch';
import { t } from '../../core/i18n/t';
import { LocalDatePipe, LocalNumberPipe } from '../../shared/i18n/locale-pipes';
import { LanguagePicker } from './language-picker';

@Component({
  selector: 'app-settings',
  imports: [
    HasRoleDirective,
    LocalNumberPipe,
    LocalDatePipe,
    KeyValuePipe,
    ThemeSwitch,
    LanguagePicker,
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

    <section *appHasRole="'ADMIN'" class="card gtfs">
      <h2>{{ t('settings.gtfsTitle') }}</h2>
      <p class="muted">{{ t('settings.gtfsIntro') }}</p>
      <button type="button" class="primary" [disabled]="running()" (click)="importBialystok()">
        {{ t(running() ? 'settings.importing' : 'settings.import') }}
      </button>

      @if (result(); as r) {
        <div class="result" role="status">
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
            <p class="muted">{{ t('settings.notLoaded', { files: r.ignoredFiles.join(', ') }) }}</p>
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
    .gtfs {
      margin-top: 1rem;
      h2 {
        font-size: 1.1rem;
        margin: 0 0 0.5rem;
      }
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

  protected readonly running = this.store.selectSignal(gtfsImportFeature.selectRunning);
  protected readonly result = this.store.selectSignal(gtfsImportFeature.selectResult);

  /** Keep the server's table order instead of sorting by name. */
  protected readonly keepOrder = () => 0;

  protected importBialystok(): void {
    this.store.dispatch(GtfsImportActions.importBialystok());
  }

  /** A table's name, as shown to people; as the server names it when not known. */
  protected label(table: string): string {
    const name = t(`settings.tables.${table}`);
    return name === `settings.tables.${table}` ? table : name;
  }
}
