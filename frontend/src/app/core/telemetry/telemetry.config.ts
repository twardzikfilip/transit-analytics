import { isDevMode } from '@angular/core';
import { APP_VERSION } from '../version';

/** Single place to tune browser telemetry. Change `endpoint` and `sampleRatio` for production. */
export const telemetryConfig = {
  /** Only in development: the server has no collector. */
  enabled: isDevMode(),
  serviceName: 'transit-frontend',
  serviceVersion: APP_VERSION,
  environment: isDevMode() ? 'local' : 'production',
  /** OTLP/HTTP base URL. In dev, `/otlp` is proxied to the collector (see proxy.conf.json). */
  endpoint: '/otlp',
  /** Fraction of traces recorded (0..1). */
  sampleRatio: 1.0,
  /** Cross-origin API URLs that should receive the `traceparent` header (same-origin calls always do). */
  propagateTraceHeaderCorsUrls: [] as RegExp[],
  metricExportIntervalMs: 30_000,
};

export const TRACER_NAME = 'transit-frontend';
