import { Component } from '@angular/core';
import { t, tn } from '../../core/i18n/t';

/** A source: its id (its texts are `sources.<id>.name`, `.gives`, `.usedOn`, `.refreshed`) and its address. */
interface Source {
  id: string;
  url: string | null;
}

/** Where the app's data comes from, one line each. */
@Component({
  selector: 'app-sources',
  template: `
    <section class="card data-table-wrap">
      <table class="data-table">
        <thead>
          <tr>
            <th>{{ t('sources.col.source') }}</th>
            <th>{{ t('sources.col.gives') }}</th>
            <th>{{ t('sources.col.usedOn') }}</th>
            <th>{{ t('sources.col.updated') }}</th>
          </tr>
        </thead>
        <tbody>
          @for (s of sources; track s.id) {
            <tr>
              <td class="name">
                @if (s.url) {
                  <a [href]="s.url" target="_blank" rel="noopener">{{
                    t('sources.' + s.id + '.name')
                  }}</a>
                } @else {
                  {{ t('sources.' + s.id + '.name') }}
                }
              </td>
              <td>{{ t('sources.' + s.id + '.gives') }}</td>
              <td>{{ t('sources.' + s.id + '.usedOn') }}</td>
              <td class="muted">{{ t('sources.' + s.id + '.refreshed') }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
    <p class="muted note" [innerHTML]="t('sources.note')"></p>
  `,
  styles: `
    .name {
      font-weight: 600;
      white-space: nowrap;
    }
    .note {
      margin-top: 0.75rem;
      font-size: 0.85rem;
    }
  `,
})
export class Sources {
  protected readonly t = t;
  protected readonly sources: Source[] = [
    { id: 'zbiorkom', url: 'https://zbiorkom.live' },
    { id: 'bkm', url: 'https://komunikacja.bialystok.pl' },
    { id: 'pks', url: 'https://files.girlc.at/gtfs/pks_nova_suprasl.zip' },
    { id: 'osm', url: 'https://www.openstreetmap.org' },
    { id: 'osrm', url: 'https://router.project-osrm.org' },
    { id: 'city', url: null },
    { id: 'census', url: null },
    { id: 'operators', url: 'https://komunikacja.bialystok.pl/pl/informacje/kontakt/' },
    { id: 'models', url: null },
  ];
}
