import { ActionReducer, createFeature, createReducer, on } from '@ngrx/store';
import { AppUser } from '../../core/models/models';
import { UsersActions } from './users.actions';

export interface UsersState {
  /** Every user, by name; null until loaded. */
  users: AppUser[] | null;
  loading: boolean;
  error: string | null;
}

const initialState: UsersState = { users: null, loading: false, error: null };

const byName = (a: AppUser, b: AppUser) =>
  a.username.toLowerCase().localeCompare(b.username.toLowerCase());

const reducer: ActionReducer<UsersState> = createReducer(
  initialState,
  on(UsersActions.load, (state) => ({ ...state, loading: true, error: null })),
  on(UsersActions.loaded, (state, { users }) => ({ ...state, users, loading: false })),
  // A user added or removed shows at once, without loading the list again.
  on(UsersActions.created, (state, { user }) => ({
    ...state,
    users: [...(state.users ?? []), user].sort(byName),
  })),
  on(UsersActions.deleted, (state, { username }) => ({
    ...state,
    users: (state.users ?? []).filter((u) => u.username !== username),
  })),
  on(UsersActions.failure, (state, { error }) => ({ ...state, loading: false, error })),
);

export const usersFeature = createFeature({ name: 'users', reducer });
