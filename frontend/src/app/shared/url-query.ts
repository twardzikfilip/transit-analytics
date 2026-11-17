import { inject } from '@angular/core';
import { Router } from '@angular/router';

/** Query parameter changes: a value sets one (an array repeats it), null removes it. */
export type QueryChanges = Record<string, string | number | string[] | null>;

/**
 * A function that merges changes into the current URL's query parameters, where every page keeps its filters (so
 * links and the back button work). Call it in an injection context, e.g. a field initializer.
 */
export function injectSetQuery(): (changes: QueryChanges, extras?: { replaceUrl?: boolean }) => void {
  const router = inject(Router);
  return (queryParams, extras = {}) =>
    void router.navigate([], { queryParams, queryParamsHandling: 'merge', ...extras });
}
