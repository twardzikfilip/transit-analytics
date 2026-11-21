import { createEntityAdapter, EntityState } from '@ngrx/entity';
import { createFeature, createReducer, on } from '@ngrx/store';
import { Route } from '../../core/models/models';
import { RoutesActions } from './routes.actions';

/** All routes. A city has few enough (Białystok: 55) to keep them all, which also feeds route pickers. */
export interface RoutesState extends EntityState<Route> {
  loading: boolean;
  error: string | null;
}

/** Natural order: 2 before 10, and lines with letters (N1) after numbers. */
export const compareRoutes = (a: Route, b: Route): number =>
  (a.sortOrder ?? 0) - (b.sortOrder ?? 0) ||
  a.name.localeCompare(b.name, undefined, { numeric: true });

const adapter = createEntityAdapter<Route>({ sortComparer: compareRoutes });

const initialState: RoutesState = adapter.getInitialState({ loading: false, error: null });

export const routesFeature = createFeature({
  name: 'routes',
  reducer: createReducer(
    initialState,
    on(RoutesActions.load, (state) => ({ ...state, loading: true, error: null })),
    on(RoutesActions.loadSuccess, (state, { routes }) =>
      adapter.setAll(routes, { ...state, loading: false }),
    ),
    on(RoutesActions.failure, (state, { error }) => ({ ...state, loading: false, error })),
  ),
  extraSelectors: ({ selectRoutesState }) => adapter.getSelectors(selectRoutesState),
});
