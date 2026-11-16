import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { Actions } from '@ngrx/effects';
import { Observable, Subject, of, throwError } from 'rxjs';
import { AuthService } from '../core/auth/auth.service';
import { GtfsApi, StopApi } from '../core/api/api.services';
import { Page, Session, Stop } from '../core/models/models';
import { AuthActions } from './auth/auth.actions';
import { login, loginSuccess, logout } from './auth/auth.effects';
import { GtfsImportActions } from './gtfs-import/gtfs-import.actions';
import { importBialystok, reloadRoutesAfterImport } from './gtfs-import/gtfs-import.effects';
import { RoutesActions } from './routes/routes.actions';
import { StopsActions } from './stops/stops.actions';
import { loadStops } from './stops/stops.effects';

function run(effect: (actions$: Actions) => Observable<unknown>, ...dispatched: unknown[]): unknown[] {
  const actions = new Subject<unknown>();
  const out: unknown[] = [];
  effect(actions as unknown as Actions).subscribe((a) => out.push(a));
  dispatched.forEach((a) => actions.next(a));
  return out;
}

describe('store effects', () => {
  const session: Session = { token: 't', username: 'ann', role: 'GUEST' };

  it('logs in, telling wrong credentials apart from other failures', () => {
    const ok = { login: () => of(session) } as unknown as AuthService;
    expect(run((a) => login(a, ok), AuthActions.login({ username: 'ann', password: 'x' }))).toEqual([
      AuthActions.loginSuccess({ session }),
    ]);

    const denied = { login: () => throwError(() => new HttpErrorResponse({ status: 401 })) } as unknown as AuthService;
    expect(run((a) => login(a, denied), AuthActions.login({ username: 'ann', password: 'x' }))).toEqual([
      AuthActions.loginFailure({ error: 'Invalid username or password' }),
    ]);

    const broken = { login: () => throwError(() => new HttpErrorResponse({ status: 503 })) } as unknown as AuthService;
    expect(run((a) => login(a, broken), AuthActions.login({ username: 'ann', password: 'x' }))).toEqual([
      AuthActions.loginFailure({ error: 'Login failed' }),
    ]);
  });

  it('saves the session and goes home after logging in; clears it and goes to the login page on logout', () => {
    const auth = { saveSession: vi.fn(), clearSession: vi.fn() } as unknown as AuthService;
    const router = { navigateByUrl: vi.fn() } as unknown as Router;
    TestBed.runInInjectionContext(() => {
      run((a) => loginSuccess(a, auth, router), AuthActions.loginSuccess({ session }));
      run((a) => logout(a, auth, router), AuthActions.logout());
    });

    expect(auth.saveSession).toHaveBeenCalledWith(session);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/home');
    expect(auth.clearSession).toHaveBeenCalled();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/login');
  });

  it('loads a page of a timetable list with the query, reporting failures', () => {
    const page = { content: [], page: { number: 0, size: 20, totalElements: 0, totalPages: 0 } } as unknown as Page<Stop>;
    const list = vi.fn(() => of(page));
    const query = { page: 0, size: 20, sort: 'name', filters: { q: 'rynek' } };
    expect(run((a) => loadStops(a, { list } as unknown as StopApi), StopsActions.load({ query }))).toEqual([
      StopsActions.loadSuccess({ result: page }),
    ]);
    expect(list).toHaveBeenCalledWith(0, 20, 'name', { q: 'rynek' });

    const failing = { list: () => throwError(() => new HttpErrorResponse({ status: 500, statusText: 'Error' })) };
    const out = run((a) => loadStops(a, failing as unknown as StopApi), StopsActions.load({ query }));
    expect((out[0] as { type: string }).type).toBe(StopsActions.failure.type);
  });

  it('imports the timetable and then reloads the routes', () => {
    const result = { feed: 'bialystok' } as never;
    const api = { importBialystok: () => of(result) } as unknown as GtfsApi;
    expect(run((a) => importBialystok(a, api), GtfsImportActions.importBialystok())).toEqual([
      GtfsImportActions.importSuccess({ result }),
    ]);
    expect(run(reloadRoutesAfterImport, GtfsImportActions.importSuccess({ result }))).toEqual([RoutesActions.load()]);
  });
});
