import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  Router,
  RouterStateSnapshot,
  UrlTree,
  provideRouter,
} from '@angular/router';
import { MockStore, provideMockStore } from '@ngrx/store/testing';
import { authFeature } from '../../store/auth/auth.reducer';
import { authGuard, roleGuard } from './auth.guards';

describe('auth guards', () => {
  let store: MockStore;
  const state = {} as RouterStateSnapshot;
  const route = (roles?: string[]) =>
    ({ data: roles ? { roles } : {} }) as unknown as ActivatedRouteSnapshot;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideMockStore()] });
    store = TestBed.inject(MockStore);
  });

  // Overrides are set on the shared memoized selectors; spec files share them (the runner doesn't isolate files).
  afterEach(() => store.resetSelectors());

  const run = (guard: typeof authGuard, r = route()) =>
    TestBed.runInInjectionContext(() => guard(r, state));
  const url = (result: unknown) => TestBed.inject(Router).serializeUrl(result as UrlTree);

  it('lets logged-in users through and sends others to the login page', () => {
    store.overrideSelector(authFeature.selectIsAuthenticated, true);
    expect(run(authGuard)).toBe(true);

    store.overrideSelector(authFeature.selectIsAuthenticated, false);
    store.refreshState();
    expect(url(run(authGuard))).toBe('/login');
  });

  it('allows only the roles listed on the route, else goes home', () => {
    store.overrideSelector(authFeature.selectRole, 'GUEST');
    expect(url(run(roleGuard, route(['ADMIN'])))).toBe('/home');
    expect(run(roleGuard, route(['ADMIN', 'GUEST']))).toBe(true);

    store.overrideSelector(authFeature.selectRole, null);
    store.refreshState();
    expect(url(run(roleGuard, route(['ADMIN', 'GUEST'])))).toBe('/home');
  });
});
