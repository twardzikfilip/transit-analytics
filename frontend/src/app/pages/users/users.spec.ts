import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { of } from 'rxjs';
import { AppUser } from '../../core/models/models';
import { UsersActions } from '../../store/users/users.actions';
import { usersFeature } from '../../store/users/users.reducer';
import { provideTestI18n } from '../../testing/i18n-testing';
import { renderPage, stateAfter, text } from '../../testing/page-testing';
import { NewUserDialog } from './new-user-dialog';
import { kinds } from './user-rules';
import { Users } from './users';

const ANN: AppUser = { username: 'ann', role: 'ADMIN', createdAt: '2026-11-20T10:00:00Z' };
const BOB: AppUser = { username: 'Bob', role: 'GUEST', createdAt: '2026-11-21T10:00:00Z' };

/** MatDialog that closes every dialog at once with `result`. */
function dialogClosingWith(result: unknown) {
  const open = vi.fn(() => ({ afterClosed: () => of(result) }));
  return { provide: MatDialog, useValue: { open } };
}

describe('Users page', () => {
  const state = (...actions: Parameters<typeof usersFeature.reducer>[1][]) => ({
    // Signed in as ann.
    auth: { session: { token: 't', username: 'ann', role: 'ADMIN' }, loading: false, error: null },
    ...stateAfter(usersFeature, ...actions),
  });

  it('lists every user, with no delete button for yourself', () => {
    const { el, store } = renderPage(Users, state(UsersActions.loaded({ users: [ANN, BOB] })));

    expect(store.dispatch).toHaveBeenCalledWith(UsersActions.load());
    const rows = [...el.querySelectorAll('tbody tr')];
    expect(rows.map((r) => text(r.children[0]))).toEqual(['ann (you)', 'Bob']);
    expect(rows.map((r) => text(r.children[1]))).toEqual(['Admin', 'Guest']);
    expect(rows[0].querySelector('button')).toBeNull();
    expect(rows[1].querySelector('button')?.getAttribute('aria-label')).toBe('Delete user Bob');
  });

  it('shows a skeleton, not a text, while loading', () => {
    const { el } = renderPage(Users, state(UsersActions.load()));
    expect(el.querySelector('app-skeleton')).not.toBeNull();
    expect(el.querySelector('table')).toBeNull();
  });

  it('adds the user the dialog closes with', () => {
    const user = { username: 'cat', password: 'Secret-pass1', role: 'GUEST' as const };
    const { el, store } = renderPage(Users, state(UsersActions.loaded({ users: [ANN, BOB] })), {}, [
      dialogClosingWith(user),
    ]);
    const dialog = TestBed.inject(MatDialog) as unknown as { open: ReturnType<typeof vi.fn> };

    (el.querySelector('.add') as HTMLButtonElement).click();

    expect(dialog.open).toHaveBeenCalledWith(
      NewUserDialog,
      expect.objectContaining({
        data: { taken: ['ann', 'Bob'] },
      }),
    );
    expect(store.dispatch).toHaveBeenCalledWith(UsersActions.create({ user }));
  });

  it('deletes a user only once confirmed', () => {
    const confirmed = renderPage(Users, state(UsersActions.loaded({ users: [ANN, BOB] })), {}, [
      dialogClosingWith(true),
    ]);
    (confirmed.el.querySelector('tbody button') as HTMLButtonElement).click();
    expect(confirmed.store.dispatch).toHaveBeenCalledWith(UsersActions.delete({ username: 'Bob' }));
  });

  it('keeps the user when the deletion is cancelled', () => {
    const { el, store } = renderPage(Users, state(UsersActions.loaded({ users: [ANN, BOB] })), {}, [
      dialogClosingWith(false),
    ]);
    (el.querySelector('tbody button') as HTMLButtonElement).click();
    expect(store.dispatch).not.toHaveBeenCalledWith(UsersActions.delete({ username: 'Bob' }));
  });

  it('shows a user added or removed at once, by name', () => {
    const after = (...actions: Parameters<typeof usersFeature.reducer>[1][]) =>
      [UsersActions.loaded({ users: [ANN, BOB] }), ...actions]
        .reduce(usersFeature.reducer, usersFeature.reducer(undefined, { type: '@@init' }))
        .users!.map((u) => u.username);
    const cat: AppUser = { username: 'cat', role: 'GUEST', createdAt: '2026-11-28T10:00:00Z' };
    expect(after(UsersActions.created({ user: cat }))).toEqual(['ann', 'Bob', 'cat']);
    expect(after(UsersActions.deleted({ username: 'Bob' }))).toEqual(['ann']);
  });
});

describe('New User dialog', () => {
  function open() {
    const ref = { close: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideTestI18n(),
        { provide: MatDialogRef, useValue: ref },
        { provide: MAT_DIALOG_DATA, useValue: { taken: ['ann', 'Bob'] } },
      ],
    });
    const fixture = TestBed.createComponent(NewUserDialog);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const type = (name: string, value: string) => {
      const input = el.querySelector(`[formcontrolname="${name}"]`) as HTMLInputElement;
      input.value = value;
      input.dispatchEvent(new Event('input'));
      input.dispatchEvent(new Event('blur'));
      fixture.detectChanges();
    };
    const errors = () => [...el.querySelectorAll('.error')].map(text);
    return { el, ref, type, errors, fixture };
  }

  it('has a title, the fields and the two buttons', () => {
    const { el } = open();
    expect(text(el.querySelector('h2'))).toBe('New User');
    expect([...el.querySelectorAll('option')].map(text)).toEqual(['Guest', 'Admin']);
    expect([...el.querySelectorAll('button')].map(text)).toEqual(['Cancel', 'Create']);
    expect((el.querySelector('button.primary') as HTMLButtonElement).disabled).toBe(true);
  });

  it('tells each broken rule of the name', () => {
    const { type, errors } = open();
    for (const [name, message] of [
      ['an', '3 to 20 characters'],
      ['a'.repeat(21), '3 to 20 characters'],
      ['an n', 'No spaces'],
      ['1ann', "Can't start with a digit"],
      ['BOB', 'This name is taken'],
    ]) {
      type('username', name);
      expect(errors()).toEqual([message]);
    }
    type('username', 'cat_2');
    expect(errors()).toEqual([]);
  });

  it('tells each broken rule of the password, and when the two differ', () => {
    const { type, errors } = open();
    type('password', 'Short1!');
    expect(errors()).toEqual(['At least 10 characters']);
    type('password', 'onlylowercase');
    expect(errors()).toEqual(['At least two of: lowercase, uppercase, digits, other characters']);
    type('password', 'lowercase12');
    type('confirm', 'lowercase13');
    expect(errors()).toEqual(["The passwords don't match"]);
  });

  it('closes with the user to add, or with nothing on Cancel', () => {
    const { el, ref, type, fixture } = open();
    type('username', 'cat');
    type('password', 'Secret-pass1');
    type('confirm', 'Secret-pass1');
    const select = el.querySelector('select') as HTMLSelectElement;
    select.value = 'ADMIN';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    (el.querySelector('button.primary') as HTMLButtonElement).click();
    expect(ref.close).toHaveBeenCalledWith({
      username: 'cat',
      password: 'Secret-pass1',
      role: 'ADMIN',
    });

    (el.querySelector('button:not(.primary)') as HTMLButtonElement).click();
    expect(ref.close).toHaveBeenLastCalledWith();
  });

  it('counts the kinds of character in a password', () => {
    expect(kinds('abcdefghij')).toBe(1);
    expect(kinds('abcdefghi1')).toBe(2);
    expect(kinds('Żółw-1')).toBe(4);
  });
});
