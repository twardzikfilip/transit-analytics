import { HttpClient, HttpContext, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ApiActivity, BACKGROUND, activityInterceptor } from './activity';

describe('activityInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let activity: ApiActivity;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([activityInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    activity = TestBed.inject(ApiActivity);
  });

  afterEach(() => backend.verify());

  it('is busy while any API request is in flight, answered or failed', () => {
    expect(activity.busy()).toBe(false);
    http.get('/api/fleet').subscribe();
    http.get('/api/lines').subscribe({ error: () => undefined });
    expect(activity.busy()).toBe(true);
    backend.expectOne('/api/fleet').flush({});
    expect(activity.busy()).toBe(true);
    backend.expectOne('/api/lines').flush(null, { status: 500, statusText: 'Failed' });
    expect(activity.busy()).toBe(false);
  });

  it('stops counting a request that is cancelled', () => {
    const sub = http.get('/api/fleet').subscribe();
    const request = backend.expectOne('/api/fleet');
    expect(activity.busy()).toBe(true);
    sub.unsubscribe();
    expect(request.cancelled).toBe(true);
    expect(activity.busy()).toBe(false);
  });

  it("ignores background polling and what isn't the API", () => {
    http
      .get('/api/zbiorkom/bialystok/positions', {
        context: new HttpContext().set(BACKGROUND, true),
      })
      .subscribe();
    http.get('/assets/x.json').subscribe();
    expect(activity.busy()).toBe(false);
    backend.expectOne('/api/zbiorkom/bialystok/positions').flush([]);
    backend.expectOne('/assets/x.json').flush({});
  });
});
