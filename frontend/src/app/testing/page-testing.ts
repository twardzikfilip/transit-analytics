import { Provider, Type } from '@angular/core';
import * as L from 'leaflet';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Action, ActionReducer } from '@ngrx/store';
import { MockStore, provideMockStore } from '@ngrx/store/testing';
import { BehaviorSubject, of } from 'rxjs';
import { BoundaryApi, BusLaneApi } from '../core/api/api.services';
import { BusLanes, CityBoundaries, Session } from '../core/models/models';

/** A small square city around a smaller centre, served to every page by default. */
export const TEST_BOUNDARIES: CityBoundaries = {
  city: [
    [53.0, 23.0],
    [53.0, 23.3],
    [53.2, 23.3],
    [53.2, 23.0],
    [53.0, 23.0],
  ],
  centre: [
    [53.12, 23.14],
    [53.12, 23.17],
    [53.14, 23.17],
    [53.12, 23.14],
  ],
  lineCentre: [
    [53.125, 23.15],
    [53.125, 23.16],
    [53.135, 23.16],
    [53.125, 23.15],
  ],
};

/** Two bus lanes: one section each way on a street, served to every page by default. */
export const TEST_BUS_LANES: BusLanes = {
  source: 'OpenStreetMap',
  fetchedAt: '2026-11-19T09:00:00Z',
  laneKm: 1.2,
  sections: 2,
  official: { km: 1, date: '2025-01-28', source: 'City', url: 'https://example.org/lanes' },
  lanes: [
    {
      id: 1,
      name: 'Lipowa',
      forward: 1,
      backward: 1,
      bothWays: true,
      points: [
        [53.13, 23.15],
        [53.13, 23.16],
      ],
    },
    {
      id: 2,
      name: 'Sienkiewicza',
      forward: 1,
      backward: 0,
      bothWays: false,
      points: [
        [53.14, 23.16],
        [53.13, 23.16],
      ],
    },
  ],
  stretches: [
    {
      id: 0,
      name: 'Lipowa',
      heading: 'eastbound',
      km: 0.67,
      paths: [
        [
          [53.13, 23.15],
          [53.13, 23.16],
        ],
      ],
    },
    {
      id: 1,
      name: 'Sienkiewicza',
      heading: 'southbound',
      km: 0.53,
      paths: [
        [
          [53.14, 23.16],
          [53.13, 23.16],
        ],
      ],
    },
  ],
};

/** A feature's state after these actions, built with its real reducer (so it always has the right shape). */
export function stateAfter<S>(
  feature: { name: string; reducer: ActionReducer<S> },
  ...actions: Action[]
) {
  return {
    [feature.name]: actions.reduce(feature.reducer, feature.reducer(undefined, { type: '@@init' })),
  };
}

/** The auth slice for a signed-in user. */
export function signedIn(role: Session['role'] = 'GUEST'): Record<string, unknown> {
  return { auth: { session: { token: 't', username: 'ann', role }, loading: false, error: null } };
}

export interface PageSetup<T> {
  fixture: ComponentFixture<T>;
  store: MockStore;
  el: HTMLElement;
  /** Changes the URL's query parameters, as navigating would. */
  setQuery: (params: Record<string, string | string[]>) => void;
  /** Re-renders after a store or input change. */
  render: () => void;
}

/**
 * Renders a page with a mock store holding `state` and a URL with `query`; every dispatch is recorded by a spy on
 * `store.dispatch`.
 */
export function renderPage<T>(
  page: Type<T>,
  state: Record<string, unknown>,
  query: Record<string, string | string[]> = {},
  providers: Provider[] = [],
): PageSetup<T> {
  const params = new BehaviorSubject(convertToParamMap(query));
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      provideMockStore({ initialState: state }),
      { provide: BoundaryApi, useValue: { boundaries: () => of(TEST_BOUNDARIES) } },
      { provide: BusLaneApi, useValue: { lanes: () => of(TEST_BUS_LANES), report: () => of() } },
      {
        provide: ActivatedRoute,
        useValue: {
          queryParamMap: params.asObservable(),
          snapshot: { queryParamMap: params.value },
        },
      },
      ...providers,
    ],
  });
  const store = TestBed.inject(MockStore);
  vi.spyOn(store, 'dispatch');
  const fixture = TestBed.createComponent(page);
  fixture.detectChanges();
  return {
    fixture,
    store,
    el: fixture.nativeElement as HTMLElement,
    setQuery: (q) => {
      params.next(convertToParamMap(q));
      fixture.detectChanges();
    },
    render: () => fixture.detectChanges(),
  };
}

/** Text of an element with whitespace collapsed. */
export const text = (el: Element | null | undefined) =>
  (el?.textContent ?? '').replace(/\s+/g, ' ').trim();

/**
 * jsdom has no canvas, so maps that prefer Leaflet's canvas renderer (many markers or lines) would fail. This makes
 * Leaflet fall back to its SVG renderer, which jsdom supports. Call it before rendering a page with such a map.
 */
export function useSvgMaps(): void {
  (L.Browser as { canvas: boolean }).canvas = false;
}
