import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';
import { GtfsApi } from '../../core/api/api.services';
import { FeedCoverage, TimetableCoverage } from '../../core/models/models';
import { LocalDatePipe } from '../../shared/i18n/locale-pipes';
import { t, tn } from '../../core/i18n/t';

/** "in 3 days", "today is the last day", "has ended" or "not loaded". */
export function coverageLeft(f: FeedCoverage): string {
  if (f.daysLeft === null) return t('home.timetables.notLoaded');
  if (f.daysLeft < 0) return t('home.timetables.ended');
  if (f.daysLeft === 0) return t('home.timetables.today');
  return tn('home.timetables.left', f.daysLeft);
}

/**
 * Timetables that end in a few days or have ended, so someone looks for a new one. Nothing while all run on, and
 * nothing while loading: usually there is nothing to say.
 */
@Component({
  selector: 'app-timetable-warning',
  imports: [LocalDatePipe],
  template: `
    @if (ending().length) {
      <section class="card tile" [attr.aria-label]="t('home.timetables.title')">
        <h2>{{ t('home.timetables.title') }}</h2>
        <p class="muted">{{ t('home.timetables.rule') }}</p>
        <ul>
          @for (f of ending(); track f.feed) {
            <li>
              <strong>{{ t('timetable.feeds.' + f.feed) }}</strong>
              <span class="tag" [class]="over(f) ? 'danger' : 'warning'">{{ left(f) }}</span>
              @if (f.lastDay) {
                <span class="muted">{{
                  t('home.timetables.lastDay', { day: f.lastDay | date: 'EEE d MMM' })
                }}</span>
              }
              @if (f.assumed.at(-1); as until) {
                <span class="muted">{{
                  t('home.timetables.assumedUntil', { day: until | date: 'EEE d MMM' })
                }}</span>
              }
            </li>
          }
        </ul>
      </section>
    }
  `,
  styles: `
    .tile {
      margin-bottom: 1.5rem;
      border: 2px solid var(--danger);
    }

    h2 {
      margin: 0 0 0.25rem;
      font-size: 1.05rem;
    }

    p {
      margin: 0 0 0.5rem;
      font-size: 0.85rem;
    }

    ul {
      margin: 0;
      padding: 0;
      list-style: none;
    }

    li {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.25rem 0.75rem;
      padding: 0.35rem 0;
      border-top: 1px solid var(--border);
      font-size: 0.9rem;
    }
  `,
})
export class TimetableWarning {
  protected readonly t = t;
  protected readonly left = coverageLeft;
  protected readonly over = (f: FeedCoverage) => f.daysLeft === null || f.daysLeft < 0;

  /** Null while loading, and after a failure (shown as a toast). */
  private readonly coverage = toSignal(
    inject(GtfsApi)
      .coverage()
      .pipe(catchError(() => of(null as TimetableCoverage | null))),
    { initialValue: null },
  );
  protected readonly ending = computed(() => this.coverage()?.feeds.filter((f) => f.ending) ?? []);
}
