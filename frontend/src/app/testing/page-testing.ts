import { Provider, Type } from '@angular/core';
import * as L from 'leaflet';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Action, ActionReducer } from '@ngrx/store';
import { MockStore, provideMockStore } from '@ngrx/store/testing';
import { BehaviorSubject } from 'rxjs';
import { Session } from '../core/models/models';

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
  setQuery: (params: Record<string, string>) => void;
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
  query: Record<string, string> = {},
  providers: Provider[] = [],
): PageSetup<T> {
  const params = new BehaviorSubject(convertToParamMap(query));
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      provideMockStore({ initialState: state }),
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
