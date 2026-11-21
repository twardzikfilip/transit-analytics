import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, map, of, startWith } from 'rxjs';
import { NetworkCoverageApi } from '../../core/api/api.services';
import { DistrictDensity, NetworkCoverage } from '../../core/models/models';
import { errorMessage } from '../../store/paged';
import { CoverageMap } from './coverage-map';
import { area, density, percent } from './coverage-format';
import { DistrictMap } from './district-map';

type Loaded =
  | { state: 'loading' }
  | { state: 'error'; error: string }
  | { state: 'ready'; data: NetworkCoverage };

/**
 * Network coverage: how much of the city lies within walking distance of a stop, with and without its woods; and the
 * districts' population densities, also without their woods.
 */
@Component({
  selector: 'app-network-coverage',
  imports: [CoverageMap, DistrictMap],
  templateUrl: './network-coverage.html',
  styleUrl: './network-coverage.scss',
})
export class NetworkCoveragePage {
  private readonly loaded = toSignal(
    inject(NetworkCoverageApi)
      .coverage()
      .pipe(
        map((data): Loaded => ({ state: 'ready', data })),
        catchError((err: HttpErrorResponse) =>
          of<Loaded>({ state: 'error', error: errorMessage(err) }),
        ),
        startWith<Loaded>({ state: 'loading' }),
      ),
    { requireSync: true },
  );

  /** Whether the coverage map is rolled down; rolled up, only its title shows (and the map is let go). */
  protected readonly coverageOpen = signal(true);

  protected readonly loading = computed(() => this.loaded().state === 'loading');
  protected readonly error = computed(() => {
    const l = this.loaded();
    return l.state === 'error' ? l.error : null;
  });
  protected readonly data = computed(() => {
    const l = this.loaded();
    return l.state === 'ready' ? l.data : null;
  });

  /** The districts by name, with the city's totals. */
  protected readonly districts = computed(() =>
    [...(this.data()?.districts ?? [])].sort((a, b) => a.name.localeCompare(b.name, 'pl')),
  );
  /** Those with large woods, the most wooded share first. */
  protected readonly wooded = computed(() =>
    this.districts()
      .filter((d) => d.largeForest)
      .sort((a, b) => b.forestKm2 / b.areaKm2 - a.forestKm2 / a.areaKm2),
  );
  /** The villages and towns around the city, the most densely lived in first; those without a figure last. */
  protected readonly belt = computed(() =>
    [...(this.data()?.belt.places ?? [])].sort(
      (a, b) => (b.landDensity ?? -1) - (a.landDensity ?? -1) || a.name.localeCompare(b.name, 'pl'),
    ),
  );
  protected readonly beltTotals = computed(() => {
    const known = this.belt().filter((p) => p.population !== null);
    return {
      places: this.belt().length,
      known: known.length,
      people: known.reduce((s, p) => s + (p.population ?? 0), 0),
      km2: this.belt().reduce((s, p) => s + p.areaKm2, 0),
    };
  });

  /** "2021 census", "31.12.2023": when a village's figure is from. */
  protected when(date: string | null): string {
    if (!date) return '';
    if (date === '2021-03-31') return '2021 census';
    const [y, m, d] = date.split('-');
    return d ? `${d}.${m}.${y}` : y;
  }

  protected readonly totals = computed(() => {
    const ds = this.districts();
    const people = ds.reduce((s, d) => s + d.population, 0);
    const km2 = ds.reduce((s, d) => s + d.areaKm2, 0);
    const land = ds.reduce((s, d) => s + d.landKm2, 0);
    return {
      people,
      km2,
      density: km2 ? people / km2 : 0,
      land,
      landDensity: land ? people / land : 0,
    };
  });

  protected readonly area = area;
  protected readonly density = density;
  protected readonly percent = percent;

  protected forestShare(d: DistrictDensity): string {
    return percent(d.areaKm2 ? d.forestKm2 / d.areaKm2 : 0);
  }

  /** "+38%": how much higher the density is without the woods. */
  protected rise(d: DistrictDensity): string {
    return d.density ? `+${Math.round((d.landDensity / d.density - 1) * 100)}%` : '–';
  }

  protected people(n: number): string {
    return n.toLocaleString('pl-PL');
  }
}
