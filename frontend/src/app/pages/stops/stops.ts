import { DecimalPipe } from '@angular/common';
import { Component, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Store } from '@ngrx/store';
import { Subject, debounceTime, distinctUntilChanged } from 'rxjs';
import { Pager } from '../../shared/pager/pager';
import { listQuery } from '../../shared/list-query';
import { StopsActions } from '../../store/stops/stops.actions';
import { stopsFeature } from '../../store/stops/stops.reducer';

@Component({
  selector: 'app-stops',
  imports: [RouterLink, Pager, DecimalPipe],
  templateUrl: './stops.html',
})
export class Stops {
  private readonly store = inject(Store);

  protected readonly stops = this.store.selectSignal(stopsFeature.selectAll);
  protected readonly page = this.store.selectSignal(stopsFeature.selectPage);
  protected readonly loading = this.store.selectSignal(stopsFeature.selectLoading);
  protected readonly error = this.store.selectSignal(stopsFeature.selectError);

  protected readonly query = listQuery({
    filters: ['q', 'zoneId'],
    size: 50,
    sort: () => 'name',
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
    const p = this.page();
    return (p ? p.number * p.size : 0) + i + 1;
  }
}
