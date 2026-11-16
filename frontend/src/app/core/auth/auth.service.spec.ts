import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Session } from '../models/models';
import { AuthService } from './auth.service';

/** A JWT whose payload expires at the given epoch seconds (the signature isn't checked here). */
function token(exp: number): string {
  const payload = btoa(JSON.stringify({ sub: 'ann', exp })).replace(/=+$/, '');
  return `header.${payload}.signature`;
}

describe('AuthService', () => {
  const inAnHour = Math.floor(Date.now() / 1000) + 3600;
  let service: AuthService;
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('posts the credentials to the login endpoint', () => {
    let session: Session | undefined;
    service.login('ann', 'secret').subscribe((s) => (session = s));

    const req = http.expectOne('/api/auth/login');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ username: 'ann', password: 'secret' });
    req.flush({ token: 't', username: 'ann', role: 'GUEST' });
    expect(session?.username).toBe('ann');
  });

  it('keeps a session across reloads until its token expires', () => {
    const session: Session = { token: token(inAnHour), username: 'ann', role: 'GUEST' };
    service.saveSession(session);
    expect(AuthService.restoreSession()).toEqual(session);

    service.saveSession({ ...session, token: token(Math.floor(Date.now() / 1000) - 60) });
    expect(AuthService.restoreSession()).toBeNull();
  });

  it('forgets a cleared, missing or unreadable session', () => {
    service.saveSession({ token: token(inAnHour), username: 'ann', role: 'GUEST' });
    service.clearSession();
    expect(AuthService.restoreSession()).toBeNull();

    localStorage.setItem('transit.session', '{not json');
    expect(AuthService.restoreSession()).toBeNull();
    localStorage.setItem('transit.session', JSON.stringify({ token: 'no-payload', username: 'x', role: 'GUEST' }));
    expect(AuthService.restoreSession()).toBeNull();
  });
});
