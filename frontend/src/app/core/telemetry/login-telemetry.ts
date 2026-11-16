import { HttpErrorResponse } from '@angular/common/http';
import { SpanStatusCode, context, trace } from '@opentelemetry/api';
import { Observable } from 'rxjs';
import { Session } from '../models/models';
import { TRACER_NAME } from './telemetry.config';

export type LoginOutcome = 'success' | 'invalid_credentials' | 'error';

/**
 * Wraps a login request so every attempt is recorded as an `auth.login` span with `user.name`, `user.roles`
 * and `login.outcome`. The HTTP request runs inside the span, so the browser → backend → SQL trace hangs off it.
 * The password is never recorded.
 *
 * Login counts come from these spans (Grafana "Transit Logins"), not from a browser-side counter: every tab
 * keeps its own cumulative total, which Prometheus can't add up correctly across browsers.
 */
export function traceLogin(username: string, login$: Observable<Session>): Observable<Session> {
  return new Observable<Session>((subscriber) => {
    const span = trace.getTracer(TRACER_NAME).startSpan('auth.login', { attributes: { 'user.name': username } });
    let ended = false;
    const finish = (outcome: LoginOutcome, role?: string, error?: unknown) => {
      if (ended) return;
      ended = true;
      span.setAttribute('login.outcome', outcome);
      if (role) span.setAttribute('user.roles', [role]);
      if (outcome === 'error') {
        span.setStatus({ code: SpanStatusCode.ERROR, message: describe(error) });
      }
      span.end();
    };

    // Subscribe inside the span's context: Angular's HttpClient starts the fetch synchronously on subscribe,
    // so the fetch instrumentation makes the HTTP span (and the backend trace) children of this span.
    const subscription = context.with(trace.setSpan(context.active(), span), () =>
      login$.subscribe({
        next: (session) => {
          finish('success', session.role);
          subscriber.next(session);
        },
        error: (error: unknown) => {
          finish(isInvalidCredentials(error) ? 'invalid_credentials' : 'error', undefined, error);
          subscriber.error(error);
        },
        complete: () => subscriber.complete(),
      }),
    );

    return () => {
      subscription.unsubscribe();
      if (!ended) {
        // Unsubscribed before the backend answered.
        ended = true;
        span.setAttribute('login.outcome', 'cancelled');
        span.end();
      }
    };
  });
}

function isInvalidCredentials(error: unknown): boolean {
  return error instanceof HttpErrorResponse && error.status === 401;
}

function describe(error: unknown): string | undefined {
  if (error instanceof HttpErrorResponse) return `HTTP ${error.status}`;
  return error instanceof Error ? error.message : undefined;
}
