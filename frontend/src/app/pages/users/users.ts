import { Component, computed, inject } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { Store } from '@ngrx/store';
import { NewUser } from '../../core/models/models';
import { authFeature } from '../../store/auth/auth.reducer';
import { UsersActions } from '../../store/users/users.actions';
import { usersFeature } from '../../store/users/users.reducer';
import { Icon } from '../../shared/icons/icon';
import { LocalDatePipe } from '../../shared/i18n/locale-pipes';
import { Skeleton } from '../../shared/skeleton/skeleton';
import { t } from '../../core/i18n/t';
import { DeleteUserDialog } from './delete-user-dialog';
import { NewUserDialog, NewUserData } from './new-user-dialog';

/** Users (admins only): every user with their role; add one in a dialog, or delete one (not yourself). */
@Component({
  selector: 'app-users',
  imports: [Icon, LocalDatePipe, Skeleton],
  templateUrl: './users.html',
  styleUrl: './users.scss',
})
export class Users {
  protected readonly t = t;
  private readonly store = inject(Store);
  private readonly dialog = inject(MatDialog);

  protected readonly users = this.store.selectSignal(usersFeature.selectUsers);
  protected readonly loading = this.store.selectSignal(usersFeature.selectLoading);
  protected readonly me = this.store.selectSignal(authFeature.selectUsername);
  private readonly taken = computed(() => (this.users() ?? []).map((u) => u.username));

  constructor() {
    this.store.dispatch(UsersActions.load());
  }

  protected add(): void {
    this.dialog
      .open<NewUserDialog, NewUserData, NewUser>(NewUserDialog, {
        data: { taken: this.taken() },
        autoFocus: 'first-tabbable',
      })
      .afterClosed()
      .subscribe((user) => {
        if (user) this.store.dispatch(UsersActions.create({ user }));
      });
  }

  protected remove(username: string): void {
    this.dialog
      .open<DeleteUserDialog, { username: string }, boolean>(DeleteUserDialog, {
        data: { username },
      })
      .afterClosed()
      .subscribe((yes) => {
        if (yes) this.store.dispatch(UsersActions.delete({ username }));
      });
  }
}
