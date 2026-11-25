import { Component } from '@angular/core';
import { t } from '../../core/i18n/t';

/**
 * A source: its id (its texts are `sources.<id>.name`, `.gives`, `.usedOn`, `.refreshed`, and `.detail` when
 * `detail` is set) and its address.
 */
interface Source {
  id: string;
  url: string | null;
  detail?: boolean;
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
              <td>
                {{ t('sources.' + s.id + '.gives') }}
                @if (s.detail) {
                  <p class="muted detail" [innerHTML]="t('sources.' + s.id + '.detail')"></p>
                }
              </td>
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
    .detail {
      margin: 0.4rem 0 0;
      font-size: 0.85rem;
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
    { id: 'zbiorkom', url: 'https://zbiorkom.live', detail: true },
    { id: 'bkm', url: 'https://komunikacja.bialystok.pl' },
    { id: 'pks', url: 'https://files.girlc.at/gtfs/pks_nova_suprasl.zip' },
    { id: 'wschodexpress', url: 'https://files.girlc.at/gtfs/wschod_express.zip' },
    { id: 'turosn', url: 'https://files.girlc.at/gtfs/turosn_koscielna.zip' },
    { id: 'rail', url: 'https://mkuran.pl/gtfs/' },
    { id: 'osm', url: 'https://www.openstreetmap.org' },
    { id: 'osrm', url: 'https://router.project-osrm.org' },
    { id: 'city', url: null },
    { id: 'census', url: null },
    { id: 'operators', url: 'https://komunikacja.bialystok.pl/pl/informacje/kontakt/' },
    { id: 'models', url: null },
    { id: 'routeTypes', url: 'https://alternativetransport.wordpress.com/2017/12/07/route-types/' },
  ];
}
