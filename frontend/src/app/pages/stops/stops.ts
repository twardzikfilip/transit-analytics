import { Component, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Store } from '@ngrx/store';
import { Subject, debounceTime, distinctUntilChanged } from 'rxjs';
import { Pager, rowNumber } from '../../shared/pager/pager';
import { listQuery } from '../../shared/list-query';
import { StopsActions } from '../../store/stops/stops.actions';
import { stopsFeature } from '../../store/stops/stops.reducer';
import { Skeleton } from '../../shared/skeleton/skeleton';
import { LocalNumberPipe } from '../../shared/i18n/locale-pipes';
import { t } from '../../core/i18n/t';

@Component({
  selector: 'app-stops',
  imports: [Skeleton, RouterLink, Pager, LocalNumberPipe],
  templateUrl: './stops.html',
})
export class Stops {
  protected readonly t = t;

  /** The timetable a stop is from, by name; a feed added in the settings without a name here shows its key. */
  protected feedName(feed: string): string {
    const key = `timetable.feeds.${feed}`;
    const name = t(key);
    return name === key ? feed : name;
  }
  private readonly store = inject(Store);

  protected readonly stops = this.store.selectSignal(stopsFeature.selectAll);
  protected readonly page = this.store.selectSignal(stopsFeature.selectPage);
  protected readonly loading = this.store.selectSignal(stopsFeature.selectLoading);

  protected readonly query = listQuery({
    filters: ['q', 'zoneId'],
    size: 50,
    onChange: (query) => this.store.dispatch(StopsActions.load({ query })),
  });

  private readonly search$ = new Subject<string>();

  constructor() {
    this.search$
      .pipe(debounceTime(300), distinctUntilChanged(), takeUntilDestroyed())
      .subscribe((q) => this.query.setFilters({ q: q.trim() }));
  }

  protected onSearch(value: string): void {
    this.search$.next(value);
  }

  protected index(i: number): number {
    return rowNumber(this.page(), i);
  }
}
