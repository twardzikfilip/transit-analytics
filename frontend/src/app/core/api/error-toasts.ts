import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { EnvironmentProviders, inject } from '@angular/core';
import { ToastrService, provideToastr } from 'ngx-toastr';
import { catchError, throwError } from 'rxjs';

/**
 * How the backend's errors reach the user: as a toast in the corner, never as text on a page. Every API answer of
 * 4xx or 5xx (and a server that can't be reached at all) shows one; the error still goes on to the caller, so a page
 * can stop its loading state. The same message again while its toast is up only restarts that toast's timer, so a map
 * that polls every few seconds doesn't stack them.
 */
export function provideErrorToasts(): EnvironmentProviders {
  return provideToastr({
    positionClass: 'toast-bottom-right',
    timeOut: 8000,
    extendedTimeOut: 4000,
    closeButton: true,
    preventDuplicates: true,
    resetTimeoutOnDuplicate: true,
    includeTitleDuplicates: true,
    // Plain text: the backend's message is never read as HTML.
    enableHtml: false,
  });
}

/** Shows a toast for every API error answer, then passes the error on. */
export const errorToastInterceptor: HttpInterceptorFn = (req, next) => {
  const toastr = inject(ToastrService);
  return next(req).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && (error.status === 0 || error.status >= 400)) {
        const { title, message } = errorToast(error, req.url);
        toastr.error(message, title);
      }
      return throwError(() => error);
    }),
  );
};

/** The toast for an error answer: what kind of failure as the title, the backend's explanation as the text. */
export function errorToast(
  error: HttpErrorResponse,
  url: string,
): { title: string; message: string } {
  if (error.status === 0) {
    return {
      title: 'No connection',
      message: "The server can't be reached. Try again in a moment.",
    };
  }
  if (error.status === 401 && url.endsWith('/api/auth/login')) {
    return { title: 'Not signed in', message: 'Invalid username or password.' };
  }
  const title =
    error.status === 401
      ? 'Signed out'
      : error.status === 403
        ? 'Not allowed'
        : error.status === 404
          ? 'Not found'
          : error.status < 500
            ? 'Request refused'
            : 'Server error';
  return { title: `${title} (${error.status})`, message: backendMessage(error) };
}

/** The backend's own explanation (problem details: `detail`, else `message` or `error`), else the HTTP status text. */
function backendMessage(error: HttpErrorResponse): string {
  const body = error.error as unknown;
  // A plain-text body; not an HTML error page (e.g. from a proxy), which would read as markup.
  if (typeof body === 'string' && body.trim() && !body.trim().startsWith('<')) return body.trim();
  if (body && typeof body === 'object') {
    const b = body as Record<string, unknown>;
    for (const key of ['detail', 'message', 'error', 'title']) {
      if (typeof b[key] === 'string' && (b[key] as string).trim()) return (b[key] as string).trim();
    }
  }
  return error.statusText && error.statusText !== 'OK' ? error.statusText : `HTTP ${error.status}`;
}
