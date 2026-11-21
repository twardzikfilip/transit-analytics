import {
  HttpClient,
  HttpErrorResponse,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApplicationRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ToastrService } from 'ngx-toastr';
import { errorToast, errorToastInterceptor, provideErrorToasts } from './error-toasts';

describe('errorToastInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let toastr: ToastrService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([errorToastInterceptor])),
        provideHttpClientTesting(),
        provideErrorToasts(),
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    toastr = TestBed.inject(ToastrService);
    vi.spyOn(toastr, 'error');
  });

  afterEach(() => {
    backend.verify();
    toastr.clear();
  });

  /** Sends a GET, answers it as given, and returns what reached the caller. */
  function answer(
    status: number,
    body: string | object | null,
    statusText = 'Error',
  ): { error?: HttpErrorResponse } {
    const got: { error?: HttpErrorResponse } = {};
    http.get('/api/fleet').subscribe({ error: (e: HttpErrorResponse) => (got.error = e) });
    backend.expectOne('/api/fleet').flush(body, { status, statusText });
    // Toasts are views of the application: render them.
    TestBed.inject(ApplicationRef).tick();
    return got;
  }

  it("toasts a 4xx answer with the backend's message, and still passes the error on", () => {
    const got = answer(404, { detail: 'No line 999' }, 'Not Found');
    expect(toastr.error).toHaveBeenCalledWith('No line 999', 'Not found (404)');
    expect(got.error?.status).toBe(404);
  });

  it("toasts a 5xx answer with the backend's message", () => {
    answer(500, { detail: 'Database unavailable' }, 'Internal Server Error');
    expect(toastr.error).toHaveBeenCalledWith('Database unavailable', 'Server error (500)');
    answer(503, null, 'Service Unavailable');
    expect(toastr.error).toHaveBeenLastCalledWith('Service Unavailable', 'Server error (503)');
  });

  it('shows the toast in the page, as text', () => {
    answer(400, { detail: '<b>from</b> must be before to' }, 'Bad Request');
    expect(toastr.error).toHaveBeenCalledTimes(1);
    const toast = document.querySelector('.toast-container .toast-error');
    expect(toast?.textContent).toContain('Request refused (400)');
    // Not read as HTML: the tags show as they are.
    expect(toast?.textContent).toContain('<b>from</b> must be before to');
    expect(toast?.querySelector('b')).toBeNull();
  });

  it('shows the same error once while its toast is up (a polling map)', () => {
    vi.mocked(toastr.error).mockRestore();
    for (let i = 0; i < 3; i++) answer(502, { detail: 'Live feed down' }, 'Bad Gateway');
    expect(document.querySelectorAll('.toast-container .toast-error')).toHaveLength(1);
  });

  it('leaves successful answers alone', () => {
    http.get('/api/fleet').subscribe();
    backend.expectOne('/api/fleet').flush({ ok: true });
    expect(toastr.error).not.toHaveBeenCalled();
  });

  it('words the other cases', () => {
    const err = (status: number, error: unknown = null, statusText = 'Error') =>
      new HttpErrorResponse({ status, error, statusText });
    expect(errorToast(err(0), '/api/fleet').title).toBe('No connection');
    expect(errorToast(err(401), '/api/auth/login')).toEqual({
      title: 'Not signed in',
      message: 'Invalid username or password.',
    });
    expect(errorToast(err(401), '/api/fleet').title).toBe('Signed out (401)');
    expect(errorToast(err(403, { message: 'Admins only' }), '/api/x')).toEqual({
      title: 'Not allowed (403)',
      message: 'Admins only',
    });
    expect(errorToast(err(422, 'Plain text reason'), '/api/x').message).toBe('Plain text reason');
    // A proxy's HTML error page isn't shown as such.
    expect(errorToast(err(502, '<html>Bad gateway</html>', 'Bad Gateway'), '/api/x').message).toBe(
      'Bad Gateway',
    );
  });
});
