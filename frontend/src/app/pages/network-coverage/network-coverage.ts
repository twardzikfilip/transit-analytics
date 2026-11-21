import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, map, of, startWith } from 'rxjs';
import { NetworkCoverageApi } from '../../core/api/api.services';
import { NetworkCoverage, PlaceDensity } from '../../core/models/models';
import { area, density, int, percent } from '../../shared/format/number';
import { asOf } from './coverage-format';
import { CoverageMap } from './coverage-map';
import { DistrictMap } from './district-map';

type Loaded = { state: 'loading' } | { state: 'error' } | { state: 'ready'; data: NetworkCoverage };

/**
 * Network coverage: how much of the city lies within each walking distance of the nearest stop, with and without its
 * woods; and the population density of the districts and of the villages and towns around the city, also without
 * their woods.
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
        // The error itself shows as a toast (core/api/error-toasts.ts).
        catchError(() => of<Loaded>({ state: 'error' })),
        startWith<Loaded>({ state: 'loading' }),
      ),
    { requireSync: true },
  );

  protected readonly loading = computed(() => this.loaded().state === 'loading');
  protected readonly data = computed(() => {
    const l = this.loaded();
    return l.state === 'ready' ? l.data : null;
  });

  /** The walking distances whose map is rolled up (only its title shows; the map is let go). */
  private readonly rolledUp = signal<ReadonlySet<number>>(new Set());

  protected isOpen(radius: number): boolean {
    return !this.rolledUp().has(radius);
  }

  protected toggle(radius: number): void {
    this.rolledUp.update((set) => {
      const next = new Set(set);
      if (!next.delete(radius)) next.add(radius);
      return next;
    });
  }

  /** The districts by name. */
  protected readonly districts = computed(() =>
    [...(this.data()?.districts ?? [])].sort((a, b) => a.name.localeCompare(b.name, 'pl')),
  );
  /** Districts with large woods or a large part of the Biała valley, the largest share without residents first. */
  protected readonly wooded = computed(() => {
    const share = this.data()?.forestShare ?? 1;
    const empty = (d: PlaceDensity) => (d.areaKm2 ? (d.forestKm2 + d.valleyKm2) / d.areaKm2 : 0);
    return this.districts()
      .filter((d) => d.largeForest || empty(d) >= share)
      .sort((a, b) => empty(b) - empty(a));
  });
  protected readonly totals = computed(() => {
    const ds = this.districts();
    const people = ds.reduce((s, d) => s + (d.population ?? 0), 0);
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

  /** The villages and towns around the city, the most densely lived in first; those without a figure last. */
  protected readonly belt = computed(() =>
    [...(this.data()?.belt.places ?? [])].sort(
      (a, b) => (b.landDensity ?? -1) - (a.landDensity ?? -1) || a.name.localeCompare(b.name, 'pl'),
    ),
  );
  protected readonly beltTotals = computed(() => {
    const places = this.belt();
    const known = places.filter((p) => p.population !== null);
    return {
      places: places.length,
      known: known.length,
      people: known.reduce((s, p) => s + (p.population ?? 0), 0),
      km2: places.reduce((s, p) => s + p.areaKm2, 0),
    };
  });

  protected readonly area = area;
  protected readonly density = density;
  protected readonly percent = percent;
  protected readonly int = int;
  protected readonly asOf = asOf;

  protected forestShare(p: PlaceDensity): string {
    return percent(p.areaKm2 ? p.forestKm2 / p.areaKm2 : 0);
  }

  /** "+38%": how much higher the density is without the woods and the valley. */
  protected rise(p: PlaceDensity): string {
    return p.density && p.landDensity
      ? `+${Math.round((p.landDensity / p.density - 1) * 100)}%`
      : '–';
  }
}
