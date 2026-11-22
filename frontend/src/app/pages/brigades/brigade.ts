import { Component, ElementRef, computed, effect, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { injectBackLink } from '../../core/title/page-back';
import { formatDelay, localTime } from '../../shared/format/time';
import { injectSetQuery } from '../../shared/url-query';
import { BrigadesActions } from '../../store/brigades/brigades.actions';
import { brigadesFeature } from '../../store/brigades/brigades.reducer';
import { BrigadeDayChart } from './brigade-day-chart';
import {
  PROBLEMS,
  kindLabel,
  actualSpan,
  busesText,
  delayText,
  share,
  span,
  tripLabel,
} from './brigade-format';
import { Skeleton } from '../../shared/skeleton/skeleton';
import { t } from '../../core/i18n/t';

/**
 * One brigade on one service day (/brigades/103-05?date=2026-11-18; without a date, today's): its trips as
 * scheduled and, under each, what actually ran: when, by which bus, how late, and what wasn't run.
 */
@Component({
  selector: 'app-brigade',
  imports: [Skeleton, BrigadeDayChart],
  templateUrl: './brigade.html',
  styleUrl: './brigades.scss',
})
export class BrigadePage {
  protected readonly t = t;
  private readonly store = inject(Store);
  private readonly router = inject(Router);
  private readonly navigate = injectSetQuery();
  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly route = inject(ActivatedRoute);
  private readonly params = toSignal(this.route.queryParamMap, { requireSync: true });
  private readonly path = toSignal(this.route.paramMap, { requireSync: true });

  protected readonly day = this.store.selectSignal(brigadesFeature.selectDay);
  protected readonly loadingDay = this.store.selectSignal(brigadesFeature.selectLoadingDay);

  protected readonly brigade = computed(() => this.path().get('brigade') ?? '');
  /** The day in the URL, else today's service day (as the backend says, with the brigade's day). */
  protected readonly date = computed(
    () => this.params().get('date') ?? this.shown()?.serviceDay ?? '',
  );
  /** The brigade's day shown: only the one asked for (not a previous one still on screen while it loads). */
  protected readonly shown = computed(() => {
    const d = this.day();
    return d && d.brigade === this.brigade() ? d : null;
  });
  /** The trip chosen on the chart or in the table. */
  protected readonly selected = signal<number | null>(null);

  protected readonly kindLabel = kindLabel;
  protected readonly delayText = delayText;
  protected readonly formatDelay = formatDelay;
  protected readonly span = span;
  protected readonly actualSpan = actualSpan;
  protected readonly busesText = busesText;
  protected readonly tripLabel = tripLabel;
  protected readonly share = share;
  protected readonly localTime = localTime;
  protected readonly problems = PROBLEMS;

  constructor() {
    injectBackLink('nav.backToBrigades', () => this.back());
    let loaded = '';
    effect(() => {
      const brigade = this.brigade();
      const date = this.params().get('date');
      const key = `${brigade}|${date}`;
      if (brigade && key !== loaded) {
        loaded = key;
        this.selected.set(null);
        this.store.dispatch(BrigadesActions.loadDay({ brigade, date }));
      }
    });
  }

  protected setDate(date: string): void {
    if (date) this.navigate({ date });
  }

  /** Back to every brigade of the same agency (the header's back button). */
  private back(): void {
    const agency = this.params().get('agency');
    void this.router.navigate(['/brigades'], { queryParams: agency ? { agency } : {} });
  }

  /** Chosen on the chart: its row in the table. */
  protected chooseTrip(i: number): void {
    this.selected.set(this.selected() === i ? null : i);
    setTimeout(() =>
      (this.host.nativeElement as HTMLElement)
        .querySelector(`tr[data-index="${i}"]`)
        ?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' }),
    );
  }
}
