import { Component, inject } from '@angular/core';
import { Store } from '@ngrx/store';
import { authFeature } from '../../store/auth/auth.reducer';

@Component({
  selector: 'app-settings',
  template: `
    <h1>Settings</h1>
    <div class="card">
      <dl>
        <dt>Username</dt>
        <dd>{{ username() }}</dd>
        <dt>Role</dt>
        <dd>{{ role() }}</dd>
      </dl>
      <p class="muted">User preferences will live here.</p>
    </div>
  `,
  styles: `
    dl {
      display: grid;
      grid-template-columns: max-content 1fr;
      gap: 0.5rem 1.5rem;
      margin: 0 0 1rem;
    }
    dt {
      color: var(--muted);
    }
    dd {
      margin: 0;
    }
  `,
})
export class Settings {
  private readonly store = inject(Store);
  protected readonly username = this.store.selectSignal(authFeature.selectUsername);
  protected readonly role = this.store.selectSignal(authFeature.selectRole);
}
