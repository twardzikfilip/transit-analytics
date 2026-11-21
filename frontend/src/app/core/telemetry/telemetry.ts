import { context, metrics, propagation, trace } from '@opentelemetry/api';
import {
  CompositePropagator,
  W3CBaggagePropagator,
  W3CTraceContextPropagator,
} from '@opentelemetry/core';
import { OTLPMetricExporter } from '@opentelemetry/exporter-metrics-otlp-http';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { registerInstrumentations } from '@opentelemetry/instrumentation';
import { DocumentLoadInstrumentation } from '@opentelemetry/instrumentation-document-load';
import { FetchInstrumentation } from '@opentelemetry/instrumentation-fetch';
import { XMLHttpRequestInstrumentation } from '@opentelemetry/instrumentation-xml-http-request';
import { resourceFromAttributes } from '@opentelemetry/resources';
import { MeterProvider, PeriodicExportingMetricReader } from '@opentelemetry/sdk-metrics';
import {
  BatchSpanProcessor,
  ParentBasedSampler,
  StackContextManager,
  TraceIdRatioBasedSampler,
  TracerProvider,
} from '@opentelemetry/sdk-trace';
import { ATTR_SERVICE_NAME, ATTR_SERVICE_VERSION } from '@opentelemetry/semantic-conventions';
import { Metric, onCLS, onFCP, onINP, onLCP, onTTFB } from 'web-vitals';
import { routeOf } from './route-of';
import { TRACER_NAME, telemetryConfig as config } from './telemetry.config';

/**
 * Sets up browser tracing (page load + every fetch/XHR, propagating `traceparent` to the backend)
 * and metrics (Core Web Vitals). Call once, before Angular bootstraps.
 *
 * The app is zoneless, so the default stack context manager is used: spans started inside a
 * synchronous call chain are linked; HTTP spans are linked to the backend via `traceparent`.
 */
export function initTelemetry(): void {
  if (!config.enabled) return;

  const base = new URL(config.endpoint, window.location.origin).href.replace(/\/$/, '');
  const resource = resourceFromAttributes({
    [ATTR_SERVICE_NAME]: config.serviceName,
    [ATTR_SERVICE_VERSION]: config.serviceVersion,
    'deployment.environment.name': config.environment,
  });

  // ---- traces ----
  const tracerProvider = new TracerProvider({
    resource,
    sampler: new ParentBasedSampler({ root: new TraceIdRatioBasedSampler(config.sampleRatio) }),
    spanProcessors: [
      new BatchSpanProcessor({ exporter: new OTLPTraceExporter({ url: `${base}/v1/traces` }) }),
    ],
  });
  trace.setGlobalTracerProvider(tracerProvider);
  context.setGlobalContextManager(new StackContextManager().enable());
  propagation.setGlobalPropagator(
    new CompositePropagator({
      propagators: [new W3CTraceContextPropagator(), new W3CBaggagePropagator()],
    }),
  );

  // Never trace the exporter's own requests. No request/response headers or bodies are recorded.
  const ignoreUrls = [new RegExp(`${escapeRegExp(config.endpoint)}/`)];
  registerInstrumentations({
    instrumentations: [
      new DocumentLoadInstrumentation(),
      new FetchInstrumentation({
        ignoreUrls,
        propagateTraceHeaderCorsUrls: config.propagateTraceHeaderCorsUrls,
        clearTimingResources: true,
      }),
      new XMLHttpRequestInstrumentation({
        ignoreUrls,
        propagateTraceHeaderCorsUrls: config.propagateTraceHeaderCorsUrls,
        clearTimingResources: true,
      }),
    ],
  });

  // ---- metrics ----
  const meterProvider = new MeterProvider({
    resource,
    readers: [
      new PeriodicExportingMetricReader({
        exporter: new OTLPMetricExporter({ url: `${base}/v1/metrics` }),
        exportIntervalMillis: config.metricExportIntervalMs,
      }),
    ],
  });
  metrics.setGlobalMeterProvider(meterProvider);
  recordWebVitals();

  // Web vitals (and the last spans) are typically final when the page is hidden — flush then.
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') {
      void tracerProvider.forceFlush();
      void meterProvider.forceFlush();
    }
  });
}

function recordWebVitals(): void {
  const meter = metrics.getMeter(TRACER_NAME);
  const histograms = {
    LCP: meter.createHistogram('web_vitals.lcp', {
      unit: 'ms',
      description: 'Largest Contentful Paint',
    }),
    INP: meter.createHistogram('web_vitals.inp', {
      unit: 'ms',
      description: 'Interaction to Next Paint',
    }),
    FCP: meter.createHistogram('web_vitals.fcp', {
      unit: 'ms',
      description: 'First Contentful Paint',
    }),
    TTFB: meter.createHistogram('web_vitals.ttfb', {
      unit: 'ms',
      description: 'Time to First Byte',
    }),
    CLS: meter.createHistogram('web_vitals.cls', {
      unit: '1',
      description: 'Cumulative Layout Shift',
    }),
  };
  const report = (m: Metric) =>
    histograms[m.name as keyof typeof histograms]?.record(m.value, {
      rating: m.rating,
      route: routeOf(window.location.pathname),
    });
  onLCP(report);
  onINP(report);
  onCLS(report);
  onFCP(report);
  onTTFB(report);
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
