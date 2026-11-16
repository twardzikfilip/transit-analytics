import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';

// Telemetry (the OpenTelemetry SDK) loads as its own chunk, started before bootstrap so it is usually ready before
// the app's first HTTP call, but without holding up the first render or the initial bundle.
import('./app/core/telemetry/telemetry').then(({ initTelemetry }) => initTelemetry());

bootstrapApplication(App, appConfig).catch((err) => console.error(err));
