import { ApplicationConfig, ErrorHandler, isDevMode, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState, provideStore } from '@ngrx/store';
import { provideStoreDevtools } from '@ngrx/store-devtools';
import { routes } from './app.routes';
import { authInterceptor } from './core/auth/auth.interceptor';
import { provideRouteTracing } from './core/telemetry/route-tracing';
import { TelemetryErrorHandler } from './core/telemetry/telemetry-error-handler';
import * as authEffects from './store/auth/auth.effects';
import { authFeature } from './store/auth/auth.reducer';
import * as livePositionsEffects from './store/live-positions/live-positions.effects';
import { livePositionsFeature } from './store/live-positions/live-positions.reducer';
import * as vehiclesEffects from './store/vehicles/vehicles.effects';
import { vehiclesFeature } from './store/vehicles/vehicles.reducer';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    { provide: ErrorHandler, useClass: TelemetryErrorHandler },
    provideRouter(routes),
    provideRouteTracing(),
    provideHttpClient(withInterceptors([authInterceptor])),
    provideStore(),
    provideState(authFeature),
    provideState(vehiclesFeature),
    provideState(livePositionsFeature),
    provideEffects(authEffects, vehiclesEffects, livePositionsEffects),
    provideStoreDevtools({ maxAge: 25, logOnly: !isDevMode() }),
  ],
};
