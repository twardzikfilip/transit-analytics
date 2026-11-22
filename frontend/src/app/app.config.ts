import {
  ApplicationConfig,
  ErrorHandler,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TitleStrategy, provideRouter } from '@angular/router';
import { PageTitle } from './core/title/page-title';
import { Theme } from './core/theme/theme';
import { provideEffects } from '@ngrx/effects';
import { provideState, provideStore } from '@ngrx/store';
import { routes } from './app.routes';
import { storeDevtools } from './store-devtools';
import { authInterceptor } from './core/auth/auth.interceptor';
import { activityInterceptor } from './core/api/activity';
import { errorToastInterceptor, provideErrorToasts } from './core/api/error-toasts';
import { provideRouteTracing } from './core/telemetry/route-tracing';
import { TelemetryErrorHandler } from './core/telemetry/telemetry-error-handler';
import * as authEffects from './store/auth/auth.effects';
import { authFeature } from './store/auth/auth.reducer';
import { provideI18n } from './core/i18n/i18n.providers';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    { provide: ErrorHandler, useClass: TelemetryErrorHandler },
    provideRouter(routes),
    { provide: TitleStrategy, useExisting: PageTitle },
    // Applies the saved colour theme before the first page shows.
    provideAppInitializer(() => void inject(Theme)),
    provideRouteTracing(),
    // Backend errors show as toasts (core/api/error-toasts.ts), never as text on a page; requests in flight show the
    // progress bar under the header (core/api/activity.ts).
    provideHttpClient(
      withInterceptors([authInterceptor, errorToastInterceptor, activityInterceptor]),
    ),
    provideErrorToasts(),
    // Translations and the language chosen in Settings (core/i18n).
    provideI18n(),
    provideStore(),
    provideState(authFeature),
    provideEffects(authEffects),
    ...storeDevtools,
  ],
};
