import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Store } from '@ngrx/store';
import { authFeature } from '../../store/auth/auth.reducer';

@Component({
  selector: 'app-home',
  imports: [RouterLink],
  template: `
    <h1>Welcome, {{ username() }}</h1>
    <p>
      You are signed in as <strong>{{ role() }}</strong>.
    </p>
    <div class="tiles">
      <a class="card" routerLink="/fleet">
        <h2>Fleet</h2>
        <p>How the buses were used, and each bus in detail.</p>
      </a>
      <a class="card" routerLink="/live-map">
        <h2>Live map</h2>
        <p>See the latest reported vehicle positions.</p>
      </a>
    </div>
  `,
  styles: `
    .tiles {
      --tile-min: 240px;
      gap: 1rem;
      margin-top: 1.5rem;
    }
    a.card {
      color: inherit;
      text-decoration: none;
    }
    a.card:hover {
      border-color: var(--accent);
    }
    h2 {
      margin: 0 0 0.25rem;
      font-size: 1.1rem;
    }
  `,
})
export class Home {
  private readonly store = inject(Store);
  protected readonly username = this.store.selectSignal(authFeature.selectUsername);
  protected readonly role = this.store.selectSignal(authFeature.selectRole);
}
