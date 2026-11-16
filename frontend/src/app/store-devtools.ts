import { EnvironmentProviders } from '@angular/core';
import { provideStoreDevtools } from '@ngrx/store-devtools';

/** NgRx devtools for development builds; production builds use store-devtools.prod.ts (none). */
export const storeDevtools: EnvironmentProviders[] = [provideStoreDevtools({ maxAge: 25 })];
