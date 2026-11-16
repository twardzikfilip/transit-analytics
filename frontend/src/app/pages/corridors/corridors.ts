import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Store } from '@ngrx/store';
import { REGULARITY_GRADES, regularityGrade, regularityMeaning } from '../line-stats/regularity';
import { CorridorsActions } from '../../store/corridors/corridors.actions';
import { corridorsFeature } from '../../store/corridors/corridors.reducer';
import { LoadMap } from './load-map';
import { CorridorSortKey, DEFAULT_SORT, nextCorridorSort, sortCorridors } from './sort';

/**
 * Important corridors: stretches of at least four consecutive stops shared by two or more lines, from the weekday
 * timetable, with their buses, intervals and regularity in peak and off-peak hours.
 */
@Component({
  selector: 'app-corridors',
  imports: [DecimalPipe, RouterLink, LoadMap],
  templateUrl: './corridors.html',
  styleUrl: './corridors.scss',
})
export class Corridors {
  private readonly store = inject(Store);

  protected readonly report = this.store.selectSignal(corridorsFeature.selectReport);
  protected readonly loading = this.store.selectSignal(corridorsFeature.selectLoading);
  protected readonly error = this.store.selectSignal(corridorsFeature.selectError);

  protected readonly sort = signal(DEFAULT_SORT);
  protected readonly corridors = computed(() =>
    sortCorridors(this.report()?.corridors ?? [], this.sort()),
  );
  protected readonly oneWay = computed(() => this.corridors().filter((c) => c.oneWay).length);

  /** "A ≤ 0.21, B ≤ 0.3, …, F above 0.74." */
  protected readonly gradeBands =
    REGULARITY_GRADES.map((g, i) =>
      Number.isFinite(g.maxCv)
        ? `${g.grade} ≤ ${g.maxCv}`
        : `${g.grade} above ${REGULARITY_GRADES[i - 1].maxCv}`,
    ).join(', ') + '.';
  protected readonly regularityGrade = regularityGrade;
  protected readonly regularityMeaning = regularityMeaning;

  constructor() {
    this.store.dispatch(CorridorsActions.load());
  }

  protected periods(list: { from: string; to: string }[] | undefined): string {
    return (list ?? []).map((p) => `${p.from}–${p.to}`).join(' and ');
  }

  protected sortBy(key: CorridorSortKey): void {
    this.sort.set(nextCorridorSort(this.sort(), key));
  }

  protected ariaSort(key: CorridorSortKey): 'ascending' | 'descending' | null {
    const s = this.sort();
    return s.key === key ? (s.descending ? 'descending' : 'ascending') : null;
  }
}
