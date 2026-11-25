import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideEffects } from '@ngrx/effects';
import { provideStore } from '@ngrx/store';
import { ToastrService } from 'ngx-toastr';
import { routes } from './app.routes';
import { provideTestI18n } from './testing/i18n-testing';
import { errorToastInterceptor, provideErrorToasts } from './core/api/error-toasts';
import { authInterceptor } from './core/auth/auth.interceptor';
import * as authEffects from './store/auth/auth.effects';
import { authFeature } from './store/auth/auth.reducer';
import { useSvgMaps } from './testing/page-testing';

/**
 * Every page, opened while the backend answers every API call with an error: the backend's message shows as a toast,
 * and never as text on the page itself.
 */

/** What the backend says; it must only ever appear in a toast. */
const BACKEND_SAYS = 'Backend explanation 7f3a: nothing to see here';

/** Each page, and the pages' parts that load on their own (a vehicle, a line, a brigade's day). */
const PAGES = [
  '/home',
  '/brigades',
  '/brigades/103-05?date=2026-11-20',
  '/vehicles',
  '/vehicles/7',
  '/trip-anomalies',
  '/hotspots',
  '/bus-lanes',
  '/delay-generation',
  '/delay-generation?line=103',
  '/routes',
  '/routes/route/103',
  '/corridors',
  '/network-coverage',
  '/stops',
  '/trips',
  '/stop-times',
  '/city-rail',
  '/sources',
  '/settings',
];

const ADMIN = { token: 'jwt', username: 'admin', role: 'ADMIN' as const };

function setUp(session: typeof ADMIN | null) {
  useSvgMaps();
  TestBed.configureTestingModule({
    providers: [
      provideRouter(routes),
      provideStore(
        { [authFeature.name]: authFeature.reducer },
        { initialState: { [authFeature.name]: { session, loading: false, error: null } } },
      ),
      provideEffects(authEffects),
      provideHttpClient(withInterceptors([authInterceptor, errorToastInterceptor])),
      provideHttpClientTesting(),
      provideErrorToasts(),
      provideTestI18n(),
    ],
  });
  return TestBed.inject(HttpTestingController);
}

/** Answers every API call (and the calls they lead to) with `status` until the page is quiet. */
async function answerAllWith(
  harness: RouterTestingHarness,
  backend: HttpTestingController,
  status: number,
) {
  let quiet = 0;
  for (let round = 0; round < 30 && quiet < 3; round++) {
    await new Promise((resolve) => setTimeout(resolve, 0));
    harness.detectChanges();
    const pending = backend.match(() => true).filter((r) => !r.cancelled);
    quiet = pending.length ? 0 : quiet + 1;
    for (const request of pending) {
      request.flush({ detail: BACKEND_SAYS }, { status, statusText: 'Failed' });
    }
  }
  await harness.fixture.whenStable();
  harness.detectChanges();
}

function toastText(): string {
  return document.querySelector('.toast-container')?.textContent ?? '';
}

describe('Backend errors show as toasts, never on the page', () => {
  afterEach(() => TestBed.inject(ToastrService).clear());

  for (const status of [404, 500]) {
    for (const url of PAGES) {
      it(`${url}: a ${status} answer is a toast only`, async () => {
        const backend = setUp(ADMIN);
        const harness = await RouterTestingHarness.create();
        // Not awaited: a page's title can wait for the backend (a bus's number), answered below.
        const navigated = harness.navigateByUrl(url);
        await answerAllWith(harness, backend, status);
        await navigated;
        if (url === '/settings') {
          // The timetable import fails too.
          (harness.fixture.nativeElement as HTMLElement)
            .querySelector<HTMLButtonElement>('section.gtfs button.primary')
            ?.click();
        }
        await answerAllWith(harness, backend, status);

        const page = (harness.fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(page, 'the page itself').not.toContain(BACKEND_SAYS);
        if (url !== '/sources') {
          // Sources asks the backend nothing.
          expect(toastText(), 'the toast').toContain(BACKEND_SAYS);
          expect(toastText()).toContain(status === 404 ? 'Not found (404)' : 'Server error (500)');
        }
      });
    }
  }

  it('a failed sign-in is a toast, not a line on the login form', async () => {
    const backend = setUp(null);
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/login');
    const form = harness.fixture.nativeElement as HTMLElement;
    const [user, password] = [...form.querySelectorAll('input')];
    user.value = 'guest';
    user.dispatchEvent(new Event('input'));
    password.value = 'wrong';
    password.dispatchEvent(new Event('input'));
    form.querySelector('form')?.dispatchEvent(new Event('submit'));
    harness.detectChanges();
    backend
      .expectOne('/api/auth/login')
      .flush({ detail: BACKEND_SAYS }, { status: 401, statusText: 'Unauthorized' });
    await answerAllWith(harness, backend, 401);

    expect(form.textContent).not.toContain(BACKEND_SAYS);
    expect(form.textContent).not.toContain('Invalid username or password');
    expect(toastText()).toContain('Invalid username or password.');
  });
});
