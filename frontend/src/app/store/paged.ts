import { HttpErrorResponse } from '@angular/common/http';
import { EntityAdapter, EntityState } from '@ngrx/entity';
import { Action, ActionCreator, createReducer, on } from '@ngrx/store';
import { Actions, ofType } from '@ngrx/effects';
import { Observable, catchError, map, of, switchMap } from 'rxjs';
import { Filters, Page, PageMeta } from '../core/models/models';

/** Shared building blocks for store slices that hold one server-side page of a large list. */

export interface PageQuery {
  /** Zero-based. */
  page: number;
  size: number;
  /** One or more "property[,asc|desc]". */
  sort: string | string[];
  filters: Filters;
}

export interface PagedState<T> extends EntityState<T> {
  /** The query the current page answers. */
  query: PageQuery | null;
  page: PageMeta | null;
  loading: boolean;
  error: string | null;
}

type Creator<P extends object> = ActionCreator<string, (props: P) => P & Action<string>>;

/**
 * What every paged slice declares, e.g.
 * {@code createActionGroup({ source: 'Stops', events: { Load: props<{ query: PageQuery }>(), ... } })}.
 */
export interface PagedActions<T> {
  load: Creator<{ query: PageQuery }>;
  loadSuccess: Creator<{ result: Page<T> }>;
  failure: Creator<{ error: string }>;
}

export function initialPagedState<T>(adapter: EntityAdapter<T>): PagedState<T> {
  return adapter.getInitialState({ query: null, page: null, loading: false, error: null });
}

export function pagedReducer<T>(actions: PagedActions<T>, adapter: EntityAdapter<T>) {
  return createReducer(
    initialPagedState(adapter),
    on(actions.load, (state, { query }) => ({ ...state, query, loading: true, error: null })),
    // Replace the rows: the store holds only the current page.
    on(actions.loadSuccess, (state, { result }) =>
      adapter.setAll(result.content, { ...state, page: result.page, loading: false }),
    ),
    on(actions.failure, (state, { error }) => ({ ...state, loading: false, error })),
  );
}

export function errorMessage(err: HttpErrorResponse): string {
  return err.error?.detail ?? err.message ?? 'Request failed';
}

/** Loads a page whenever `load` is dispatched; a newer query cancels one still in flight. */
export function pagedLoadEffect<T>(actions: PagedActions<T>, fetch: (query: PageQuery) => Observable<Page<T>>) {
  return (actions$: Actions) =>
    actions$.pipe(
      ofType(actions.load),
      switchMap(({ query }) =>
        fetch(query).pipe(
          map((result) => actions.loadSuccess({ result })),
          catchError((err: HttpErrorResponse) => of(actions.failure({ error: errorMessage(err) }))),
        ),
      ),
    );
}


