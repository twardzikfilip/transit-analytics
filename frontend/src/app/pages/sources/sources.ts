import { Component } from '@angular/core';

interface Source {
  name: string;
  url: string | null;
  gives: string;
  usedOn: string;
  refreshed: string;
}

/** Where the app's data comes from, one line each. */
@Component({
  selector: 'app-sources',
  template: `
    <section class="card data-table-wrap">
      <table class="data-table">
        <thead>
          <tr>
            <th>Source</th>
            <th>What it gives</th>
            <th>Used on</th>
            <th>Updated</th>
          </tr>
        </thead>
        <tbody>
          @for (s of sources; track s.name) {
            <tr>
              <td class="name">
                @if (s.url) {
                  <a [href]="s.url" target="_blank" rel="noopener">{{ s.name }}</a>
                } @else {
                  {{ s.name }}
                }
              </td>
              <td>{{ s.gives }}</td>
              <td>{{ s.usedOn }}</td>
              <td class="muted">{{ s.refreshed }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
    <p class="muted note">
      Map data © OpenStreetMap contributors (ODbL). Settings and hand-kept figures live in the
      backend's
      <code>resources/map</code>, <code>classification</code> and <code>places</code> folders; each
      file names its source.
    </p>
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
  protected readonly sources: Source[] = [
    {
      name: 'zbiorkom.live',
      url: 'https://zbiorkom.live',
      gives:
        'Live vehicle positions, delays, brigades and models, every 10 s; recorded into the database.',
      usedOn:
        'Home, Fleet, Brigades, Abnormal trips, Stop hotspots, Bus lanes, Delay generation, Routes',
      refreshed: 'Continuously',
    },
    {
      name: 'BKM timetable (GTFS)',
      url: 'https://komunikacja.bialystok.pl',
      gives: 'City lines, stops, trips, stop times and route shapes.',
      usedOn: 'Routes, Corridors, Stops, Trips, Stop times; schedules everywhere',
      refreshed: 'Import in Settings',
    },
    {
      name: 'PKS Nova line 500 (GTFS)',
      url: 'https://files.girlc.at/gtfs/pks_nova_suprasl.zip',
      gives: 'Timetable of the Białystok – Supraśl line (unofficial feed, CC0).',
      usedOn: 'Routes, Brigades',
      refreshed: 'Nightly',
    },
    {
      name: 'OpenStreetMap',
      url: 'https://www.openstreetmap.org',
      gives:
        'Base map; city border, districts, woods, nearby villages, bus lanes and depot outlines (via Overpass).',
      usedOn: 'All maps, Network coverage, Bus lanes, line classes',
      refreshed: 'Bus lanes monthly; the rest by hand',
    },
    {
      name: 'OSRM demo server',
      url: 'https://router.project-osrm.org',
      gives: 'Likely driving routes between depots and the first or last stop.',
      usedOn: 'Routes (depot runs)',
      refreshed: 'On demand',
    },
    {
      name: 'Białystok city report 2025',
      url: null,
      gives: 'Residents per district (permanent and temporary) and the GUS city total.',
      usedOn: 'Network coverage',
      refreshed: 'Yearly, by hand',
    },
    {
      name: 'Census 2021, gminas, Wikipedia',
      url: null,
      gives: 'Residents of the villages within 5 km of the city.',
      usedOn: 'Network coverage',
      refreshed: 'By hand',
    },
    {
      name: 'BKM operators and EU funds page',
      url: 'https://komunikacja.bialystok.pl/pl/informacje/kontakt/',
      gives: 'Which company runs which buses; the official length of bus lanes (24 km).',
      usedOn: 'Fleet, Bus lanes',
      refreshed: 'By hand',
    },
    {
      name: 'Vehicle models (own list)',
      url: null,
      gives: 'Length, class and capacity of each bus model.',
      usedOn: 'Fleet, Corridors',
      refreshed: 'By hand',
    },
  ];
}
