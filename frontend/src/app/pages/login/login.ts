import { Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Store } from '@ngrx/store';
import { AuthActions } from '../../store/auth/auth.actions';
import { authFeature } from '../../store/auth/auth.reducer';
import { t } from '../../core/i18n/t';

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule],
  templateUrl: './login.html',
  styleUrl: './login.scss',
})
export class Login {
  protected readonly t = t;
  private readonly store = inject(Store);

  protected readonly loading = this.store.selectSignal(authFeature.selectLoading);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    username: ['', Validators.required],
    password: ['', Validators.required],
  });

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.store.dispatch(AuthActions.login(this.form.getRawValue()));
  }
}
