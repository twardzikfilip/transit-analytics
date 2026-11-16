import { ErrorHandler, Injectable } from '@angular/core';
import { SpanStatusCode, trace } from '@opentelemetry/api';
import { TRACER_NAME } from './telemetry.config';
import { routeOf } from './route-of';

/** Reports uncaught errors as `ui.error` spans, then logs them to the console as Angular normally does. */
@Injectable()
export class TelemetryErrorHandler extends ErrorHandler {
  override handleError(error: unknown): void {
    try {
      const span = trace.getTracer(TRACER_NAME).startSpan('ui.error', {
        attributes: { route: routeOf(window.location.pathname) },
      });
      span.recordException(error instanceof Error ? error : String(error));
      span.setStatus({ code: SpanStatusCode.ERROR, message: error instanceof Error ? error.message : undefined });
      span.end();
    } catch {
      /* telemetry must never break error handling */
    }
    super.handleError(error);
  }
}
