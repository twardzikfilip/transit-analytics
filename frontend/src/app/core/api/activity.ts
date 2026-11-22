import { HttpContextToken, HttpInterceptorFn } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { finalize } from 'rxjs';

/** Marks a request that runs in the background (a map's polling): it doesn't show the progress bar. */
export const BACKGROUND = new HttpContextToken<boolean>(() => false);

/** Whether the app is waiting for the backend: requests to /api in flight. */
@Injectable({ providedIn: 'root' })
export class ApiActivity {
  private readonly pending = signal(0);
  readonly busy = computed(() => this.pending() > 0);

  start(): void {
    this.pending.update((n) => n + 1);
  }

  end(): void {
    this.pending.update((n) => Math.max(0, n - 1));
  }
}

/** Counts each /api request until it ends (answered, failed or cancelled), for the progress bar in the shell. */
export const activityInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/api') || req.context.get(BACKGROUND)) return next(req);
  const activity = inject(ApiActivity);
  activity.start();
  return next(req).pipe(finalize(() => activity.end()));
};
