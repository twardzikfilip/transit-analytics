import { SpanStatusCode, trace } from '@opentelemetry/api';
import {
  InMemorySpanExporter,
  SimpleSpanProcessor,
  TracerProvider,
} from '@opentelemetry/sdk-trace';
import { TelemetryErrorHandler } from './telemetry-error-handler';
import { routeOf } from './route-of';

describe('TelemetryErrorHandler', () => {
  const exporter = new InMemorySpanExporter();

  beforeEach(() => {
    trace.setGlobalTracerProvider(
      new TracerProvider({ spanProcessors: [new SimpleSpanProcessor({ exporter })] }),
    );
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
  });

  afterEach(() => {
    trace.disable();
    exporter.reset();
    vi.restoreAllMocks();
  });

  it('records the error as a ui.error span and still logs it', () => {
    new TelemetryErrorHandler().handleError(new Error('boom'));

    const [span] = exporter.getFinishedSpans();
    expect(span.name).toBe('ui.error');
    expect(span.status.code).toBe(SpanStatusCode.ERROR);
    expect(span.events.some((e) => e.name === 'exception')).toBe(true);
    expect(console.error).toHaveBeenCalled();
  });
});

describe('routeOf', () => {
  it('collapses ids so routes stay low-cardinality', () => {
    expect(routeOf('/vehicles/42')).toBe('/vehicles/:id');
    expect(routeOf('/live-map')).toBe('/live-map');
    expect(routeOf('')).toBe('/');
  });
});
