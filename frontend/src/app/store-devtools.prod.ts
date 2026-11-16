import { EnvironmentProviders } from '@angular/core';

/** Production builds: no NgRx devtools (keeps them out of the bundle). */
export const storeDevtools: EnvironmentProviders[] = [];
