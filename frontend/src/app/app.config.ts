import {
  ApplicationConfig,
  ErrorHandler,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState, provideStore } from '@ngrx/store';
import { routes } from './app.routes';
import { storeDevtools } from './store-devtools';
import { authInterceptor } from './core/auth/auth.interceptor';
import { provideRouteTracing } from './core/telemetry/route-tracing';
import { TelemetryErrorHandler } from './core/telemetry/telemetry-error-handler';
import * as authEffects from './store/auth/auth.effects';
import { authFeature } from './store/auth/auth.reducer';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    { provide: ErrorHandler, useClass: TelemetryErrorHandler },
    provideRouter(routes),
    provideRouteTracing(),
    provideHttpClient(withInterceptors([authInterceptor])),
    provideStore(),
    provideState(authFeature),
    provideEffects(authEffects),
    ...storeDevtools,
  ],
};
