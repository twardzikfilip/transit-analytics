import { DecimalPipe } from '@angular/common';
import { Component, computed, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Corridor } from '../../core/models/models';
import { regularityGrade, regularityMeaning } from '../../shared/regularity';
import { CorridorSortKey, DEFAULT_SORT, nextCorridorSort, sortCorridors } from './sort';

/**
 * The corridors in a sortable table (busiest first): stops (expandable), lines (outlined when running only part of
 * the corridor), length, buses, and interval and regularity in peak and off-peak hours.
 */
@Component({
  selector: 'app-corridor-table',
  imports: [DecimalPipe, RouterLink],
  templateUrl: './corridor-table.html',
  styleUrl: './corridor-table.scss',
})
export class CorridorTable {
  readonly corridors = input.required<Corridor[]>();
  readonly loading = input(false);

  protected readonly sort = signal(DEFAULT_SORT);
  protected readonly sorted = computed(() => sortCorridors(this.corridors(), this.sort()));
  protected readonly regularityGrade = regularityGrade;
  protected readonly regularityMeaning = regularityMeaning;

  protected sortBy(key: CorridorSortKey): void {
    this.sort.set(nextCorridorSort(this.sort(), key));
  }

  protected ariaSort(key: CorridorSortKey): 'ascending' | 'descending' | null {
    const s = this.sort();
    return s.key === key ? (s.descending ? 'descending' : 'ascending') : null;
  }
}
