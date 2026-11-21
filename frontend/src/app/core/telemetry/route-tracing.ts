import { EnvironmentProviders, inject, provideAppInitializer } from '@angular/core';
import { Span, SpanStatusCode, trace } from '@opentelemetry/api';
import {
  NavigationCancel,
  NavigationEnd,
  NavigationError,
  NavigationStart,
  Router,
} from '@angular/router';
import { TRACER_NAME } from './telemetry.config';
import { routeOf } from './route-of';

/** Emits one `navigation` span per Angular route change (path only, never query strings). */
export function provideRouteTracing(): EnvironmentProviders {
  return provideAppInitializer(() => {
    const router = inject(Router);
    const tracer = trace.getTracer(TRACER_NAME);
    let current: Span | undefined;

    router.events.subscribe((event) => {
      if (event instanceof NavigationStart) {
        current?.end();
        const route = routeOf(event.url.split(/[?#]/)[0]);
        current = tracer.startSpan('navigation', { attributes: { route } });
      } else if (event instanceof NavigationEnd || event instanceof NavigationCancel) {
        current?.end();
        current = undefined;
      } else if (event instanceof NavigationError) {
        current?.setStatus({ code: SpanStatusCode.ERROR });
        current?.end();
        current = undefined;
      }
    });
  });
}
