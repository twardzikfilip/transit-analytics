import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { MockStore, provideMockStore } from '@ngrx/store/testing';
import { AuthActions } from '../../store/auth/auth.actions';
import { authFeature } from '../../store/auth/auth.reducer';
import { authInterceptor } from './auth.interceptor';

describe('authInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let store: MockStore;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        provideMockStore({ selectors: [{ selector: authFeature.selectToken, value: 'jwt' }] }),
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    store = TestBed.inject(MockStore);
    vi.spyOn(store, 'dispatch');
  });

  afterEach(() => backend.verify());

  it('sends the token with API calls but not with the login itself', () => {
    http.get('/api/vehicles').subscribe();
    expect(backend.expectOne('/api/vehicles').request.headers.get('Authorization')).toBe('Bearer jwt');

    http.post('/api/auth/login', {}).subscribe();
    expect(backend.expectOne('/api/auth/login').request.headers.has('Authorization')).toBe(false);
  });

  it('logs out when the backend answers 401, and passes the error on', () => {
    let status = 0;
    http.get('/api/vehicles').subscribe({ error: (e) => (status = e.status) });
    backend.expectOne('/api/vehicles').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(status).toBe(401);
    expect(store.dispatch).toHaveBeenCalledWith(AuthActions.logout());
  });

  it('does not log out for other errors or a failed login', () => {
    http.get('/api/vehicles').subscribe({ error: () => undefined });
    backend.expectOne('/api/vehicles').flush(null, { status: 500, statusText: 'Error' });
    http.post('/api/auth/login', {}).subscribe({ error: () => undefined });
    backend.expectOne('/api/auth/login').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(store.dispatch).not.toHaveBeenCalled();
  });
});
