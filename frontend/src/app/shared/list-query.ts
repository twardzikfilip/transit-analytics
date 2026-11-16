import { DestroyRef, inject } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, ParamMap, Router } from '@angular/router';
import { Filters } from '../core/models/models';
import { PageQuery } from '../store/paged';

/**
 * Keeps a list page's filters and page number in the URL (e.g. /trips?routeId=17&page=2), so links can point at
 * a filtered list and the back button works. `onChange` runs with the query for the current URL, and again
 * whenever it changes.
 */
export function listQuery(options: {
  filters: string[];
  size: number;
  /** Sort for the current filters, e.g. by stop sequence when looking at one trip. */
  sort: (filters: Filters) => string | string[];
  onChange: (query: PageQuery) => void;
}) {
  const route = inject(ActivatedRoute);
  const router = inject(Router);
  const params = toSignal(route.queryParamMap, { requireSync: true });

  const read = (map: ParamMap): PageQuery => {
    const filters: Filters = {};
    for (const key of options.filters) filters[key] = map.get(key);
    const page = Math.max(1, Number(map.get('page')) || 1);
    return { page: page - 1, size: options.size, sort: options.sort(filters), filters };
  };

  route.queryParamMap
    .pipe(takeUntilDestroyed(inject(DestroyRef)))
    .subscribe((map) => options.onChange(read(map)));

  return {
    /** Current value of a filter in the URL ('' when unset). */
    get: (key: string) => params().get(key) ?? '',
    /** Changes filters and goes back to page 1; null or '' removes a filter. */
    setFilters: (changes: Record<string, string | number | null>) => {
      const queryParams: Record<string, string | number | null> = { page: null };
      for (const [key, value] of Object.entries(changes)) queryParams[key] = value === '' ? null : value;
      void router.navigate([], { queryParams, queryParamsHandling: 'merge' });
    },
    /** Zero-based page index. */
    setPage: (page: number) =>
      void router.navigate([], { queryParams: { page: page > 0 ? page + 1 : null }, queryParamsHandling: 'merge' }),
  };
}
