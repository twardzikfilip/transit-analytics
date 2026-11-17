import { Routes } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideState } from '@ngrx/store';
import * as delayGenerationEffects from '../../store/delay-generation/delay-generation.effects';
import { delayGenerationFeature } from '../../store/delay-generation/delay-generation.reducer';
import { DelayGeneration } from './delay-generation';

/** Delay generation, with its store slice, loaded on first visit. */
export const DELAY_GENERATION_ROUTES: Routes = [
  {
    path: '',
    providers: [provideState(delayGenerationFeature), provideEffects(delayGenerationEffects)],
    component: DelayGeneration,
  },
];
