import { HttpErrorResponse } from '@angular/common/http';
import { SpanStatusCode, context, trace } from '@opentelemetry/api';
import {
  InMemorySpanExporter,
  SimpleSpanProcessor,
  StackContextManager,
  TracerProvider,
} from '@opentelemetry/sdk-trace';
import { Observable, lastValueFrom, of, throwError } from 'rxjs';
import { Session } from '../models/models';
import { traceLogin } from './login-telemetry';

describe('traceLogin', () => {
  const spans = new InMemorySpanExporter();

  const session: Session = { token: 'secret-token', username: 'admin', role: 'ADMIN' };

  beforeEach(() => {
    // Same context manager as the app (initTelemetry); without one, context.with() is a no-op.
    context.setGlobalContextManager(new StackContextManager().enable());
    trace.setGlobalTracerProvider(new TracerProvider({ spanProcessors: [new SimpleSpanProcessor({ exporter: spans })] }));
  });

  afterEach(() => {
    trace.disable();
    context.disable();
    spans.reset();
  });

  it('records a successful login with username and role, but no token', async () => {
    await lastValueFrom(traceLogin('admin', of(session)));

    const [span] = spans.getFinishedSpans();
    expect(span.name).toBe('auth.login');
    expect(span.attributes['user.name']).toBe('admin');
    expect(span.attributes['user.roles']).toEqual(['ADMIN']);
    expect(span.attributes['login.outcome']).toBe('success');
    expect(span.status.code).not.toBe(SpanStatusCode.ERROR);
    expect(JSON.stringify(span.attributes)).not.toContain('secret-token');
  });

  it('records wrong credentials as a failed login, not as an error', async () => {
    const denied = throwError(() => new HttpErrorResponse({ status: 401 }));
    await expect(lastValueFrom(traceLogin('admin', denied))).rejects.toBeInstanceOf(HttpErrorResponse);

    const [span] = spans.getFinishedSpans();
    expect(span.attributes['login.outcome']).toBe('invalid_credentials');
    expect(span.status.code).not.toBe(SpanStatusCode.ERROR);
  });

  it('marks server failures as errors', async () => {
    const broken = throwError(() => new HttpErrorResponse({ status: 500 }));
    await expect(lastValueFrom(traceLogin('admin', broken))).rejects.toBeInstanceOf(HttpErrorResponse);

    const [span] = spans.getFinishedSpans();
    expect(span.attributes['login.outcome']).toBe('error');
    expect(span.status.code).toBe(SpanStatusCode.ERROR);
  });

  it('runs the request inside the login span so the HTTP span becomes its child', async () => {
    let activeTraceId: string | undefined;
    const request$ = new Observable<Session>((s) => {
      activeTraceId = trace.getActiveSpan()?.spanContext().traceId;
      s.next(session);
      s.complete();
    });

    await lastValueFrom(traceLogin('admin', request$));

    expect(activeTraceId).toBe(spans.getFinishedSpans()[0].spanContext().traceId);
  });
});
