import { HttpErrorResponse } from '@angular/common/http';
import { Actions } from '@ngrx/effects';
import { of, throwError } from 'rxjs';
import { VersionApi } from '../../core/api/api.services';
import { VersionsActions } from './versions.actions';
import { load } from './versions.effects';
import { versionsFeature } from './versions.reducer';

describe('Versions', () => {
  const versions = { backend: { version: '1.2.3', released: '2026-11-24' }, recorder: null };
  const run = (api: unknown) => {
    const out: unknown[] = [];
    load(of(VersionsActions.load()) as unknown as Actions, api as VersionApi).subscribe((a) =>
      out.push(a),
    );
    return out;
  };

  it('loads them, or keeps the error', () => {
    expect(run({ versions: () => of(versions) })).toEqual([VersionsActions.loaded({ versions })]);
    const fails = () =>
      throwError(() => new HttpErrorResponse({ status: 500, error: { detail: 'Down' } }));
    expect(run({ versions: fails })).toEqual([VersionsActions.failure({ error: 'Down' })]);
  });

  it('are kept', () => {
    let state = versionsFeature.reducer(undefined, VersionsActions.load());
    expect(state.loading).toBe(true);
    state = versionsFeature.reducer(state, VersionsActions.loaded({ versions }));
    expect(state).toEqual({ versions, loading: false, error: null });
    state = versionsFeature.reducer(state, VersionsActions.failure({ error: 'Down' }));
    expect(state).toEqual({ versions, loading: false, error: 'Down' });
  });
});
