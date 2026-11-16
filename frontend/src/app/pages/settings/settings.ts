import { DatePipe, DecimalPipe, KeyValuePipe } from '@angular/common';
import { Component, inject } from '@angular/core';
import { Store } from '@ngrx/store';
import { HasRoleDirective } from '../../core/auth/has-role.directive';
import { authFeature } from '../../store/auth/auth.reducer';
import { GtfsImportActions } from '../../store/gtfs-import/gtfs-import.actions';
import { gtfsImportFeature } from '../../store/gtfs-import/gtfs-import.reducer';

/** Table names from the import result, as shown to people. */
const TABLE_LABELS: Record<string, string> = {
  agency: 'Agencies',
  route: 'Routes',
  stop: 'Stops',
  trip: 'Trips',
  stop_time: 'Stop times',
  calendar_date: 'Calendar dates',
  shape_point: 'Shape points',
};

@Component({
  selector: 'app-settings',
  imports: [HasRoleDirective, DecimalPipe, DatePipe, KeyValuePipe],
  template: `
    <h1>Settings</h1>
    <div class="card">
      <dl>
        <dt>Username</dt>
        <dd>{{ username() }}</dd>
        <dt>Role</dt>
        <dd>{{ role() }}</dd>
      </dl>
      <p class="muted">User preferences will live here.</p>
    </div>

    <section *appHasRole="'ADMIN'" class="card gtfs">
      <h2>Timetable data (GTFS)</h2>
      <p class="muted">
        Downloads Białystok's current timetable from komunikacja.bialystok.pl and replaces routes, stops, trips and stop
        times with it. Takes about half a minute; the old timetable stays available until the new one is loaded.
      </p>
      <button type="button" class="primary" [disabled]="running()" (click)="importBialystok()">
        {{ running() ? 'Importing…' : 'Import Białystok timetable' }}
      </button>

      @if (error(); as message) {
        <p class="error" role="alert">Import failed: {{ message }}</p>
      }

      @if (result(); as r) {
        <div class="result" role="status">
          <p>
            Imported {{ r.importedAt | date: 'medium' }} in {{ r.durationMs / 1000 | number: '1.0-1' }} s
            ({{ r.downloadedBytes / 1048576 | number: '1.1-1' }} MB download).
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
            <p class="muted">Skipped {{ s.value | number }} rows of {{ s.key }} that point at a missing route, trip or stop.</p>
          }
          @if (r.ignoredFiles.length) {
            <p class="muted">Not loaded: {{ r.ignoredFiles.join(', ') }}</p>
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
  protected readonly username = this.store.selectSignal(authFeature.selectUsername);
  protected readonly role = this.store.selectSignal(authFeature.selectRole);

  protected readonly running = this.store.selectSignal(gtfsImportFeature.selectRunning);
  protected readonly result = this.store.selectSignal(gtfsImportFeature.selectResult);
  protected readonly error = this.store.selectSignal(gtfsImportFeature.selectError);

  /** Keep the server's table order instead of sorting by name. */
  protected readonly keepOrder = () => 0;

  protected importBialystok(): void {
    this.store.dispatch(GtfsImportActions.importBialystok());
  }

  protected label(table: string): string {
    return TABLE_LABELS[table] ?? table;
  }
}
