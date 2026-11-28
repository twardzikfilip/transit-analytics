import { Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { NewUser, UserRole } from '../../core/models/models';
import { t } from '../../core/i18n/t';
import {
  NAME_MAX,
  NAME_MIN,
  PASSWORD_MIN,
  nameFree,
  nameShape,
  passwordKinds,
  passwordsMatch,
} from './user-rules';

/** The names already taken, to tell a new one isn't. */
export interface NewUserData {
  taken: readonly string[];
}

/** The New User dialog: a name, a password typed twice, and a role. Closes with the user to add, or nothing. */
@Component({
  selector: 'app-new-user-dialog',
  imports: [ReactiveFormsModule],
  templateUrl: './new-user-dialog.html',
  styleUrl: './dialog.scss',
})
export class NewUserDialog {
  protected readonly t = t;
  private readonly ref = inject<MatDialogRef<NewUserDialog, NewUser>>(MatDialogRef);
  private readonly data = inject<NewUserData>(MAT_DIALOG_DATA);
  protected readonly limits = { min: NAME_MIN, max: NAME_MAX, password: PASSWORD_MIN };
  protected readonly roles: UserRole[] = ['GUEST', 'ADMIN'];

  protected readonly form = inject(FormBuilder).nonNullable.group(
    {
      username: [
        '',
        [
          Validators.required,
          Validators.minLength(NAME_MIN),
          Validators.maxLength(NAME_MAX),
          nameShape,
          nameFree(() => this.data.taken),
        ],
      ],
      password: ['', [Validators.required, Validators.minLength(PASSWORD_MIN), passwordKinds]],
      confirm: ['', Validators.required],
      role: ['GUEST' as UserRole, Validators.required],
    },
    { validators: passwordsMatch },
  );

  /** The message for a field's first broken rule, once it was touched; else null. */
  protected error(field: 'username' | 'password' | 'confirm'): string | null {
    const control = this.form.controls[field];
    if (!control.touched) return null;
    const e = control.errors;
    if (field === 'confirm') {
      return e?.['required']
        ? t('users.errors.required')
        : this.form.errors?.['mismatch']
          ? t('users.errors.mismatch')
          : null;
    }
    if (!e) return null;
    if (e['required']) return t('users.errors.required');
    if (field === 'username') {
      if (e['spaces']) return t('users.errors.spaces');
      if (e['startsWithDigit']) return t('users.errors.startsWithDigit');
      if (e['minlength'] || e['maxlength'])
        return t('users.errors.nameLength', { min: NAME_MIN, max: NAME_MAX });
      if (e['taken']) return t('users.errors.taken');
    }
    if (e['minlength']) return t('users.errors.passwordLength', { min: PASSWORD_MIN });
    if (e['kinds']) return t('users.errors.kinds');
    return null;
  }

  protected create(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { username, password, role } = this.form.getRawValue();
    this.ref.close({ username: username.trim(), password, role });
  }

  protected cancel(): void {
    this.ref.close();
  }
}
