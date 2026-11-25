import { Component, computed, inject, input } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';
import { GtfsApi } from '../core/api/api.services';
import { TimetableCoverage } from '../core/models/models';
import { LocalDatePipe } from './i18n/locale-pipes';
import { t } from '../core/i18n/t';

export interface AssumedDays {
  feed: string;
  from: string;
  to: string;
}

/** Local day of an instant, or the day itself ("2026-11-29"). */
function day(value: string): string {
  return value.length === 10 ? value : new Date(value).toLocaleDateString('sv');
}

/** Each feed's assumed days from one day or instant to another (one feed only, if given). */
export function assumedDays(
  coverage: TimetableCoverage | null,
  from: string,
  to: string,
  feed: string | null,
): AssumedDays[] {
  return (coverage?.feeds ?? [])
    .filter((f) => feed === null || f.feed === feed)
    .flatMap((f) => {
      const days = f.assumed.filter((d) => d >= day(from) && d <= day(to));
      return days.length ? [{ feed: f.feed, from: days[0], to: days[days.length - 1] }] : [];
    });
}

/** Says which operators' timetables are assumed in the days shown; nothing when all are published ones. */
@Component({
  selector: 'app-assumed-timetable',
  imports: [LocalDatePipe],
  template: `
    @for (a of assumed(); track a.feed) {
      <p class="muted note">
        <span class="tag warning">{{ t('timetable.assumed.tag') }}</span>
        {{
          t('timetable.assumed.note', {
            operator: t('timetable.feeds.' + a.feed),
            from: a.from | date: 'd MMM',
            to: a.to | date: 'd MMM',
          })
        }}
      </p>
    }
  `,
  styles: `
    p {
      margin: 0 0 0.75rem;
      font-size: 0.85rem;
    }
  `,
})
export class AssumedTimetable {
  protected readonly t = t;
  readonly from = input.required<string>();
  readonly to = input.required<string>();
  readonly feed = input<string | null>(null);

  /** Null while loading, and after a failure (shown as a toast). */
  private readonly coverage = toSignal(
    inject(GtfsApi)
      .coverage()
      .pipe(catchError(() => of(null as TimetableCoverage | null))),
    { initialValue: null },
  );
  protected readonly assumed = computed(() =>
    assumedDays(this.coverage(), this.from(), this.to(), this.feed()),
  );
}
