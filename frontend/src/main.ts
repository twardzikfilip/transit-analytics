import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';
import { initTelemetry } from './app/core/telemetry/telemetry';

// Before bootstrap so the initial page load and first HTTP calls are traced.
initTelemetry();

bootstrapApplication(App, appConfig)
  .catch((err) => console.error(err));
