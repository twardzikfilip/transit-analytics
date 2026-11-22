import { Component, computed, effect, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { BrigadeRef } from '../../core/models/models';
import { injectSetQuery } from '../../shared/url-query';
import { BrigadesActions } from '../../store/brigades/brigades.actions';
import { brigadesFeature } from '../../store/brigades/brigades.reducer';
import { Skeleton } from '../../shared/skeleton/skeleton';
import { t } from '../../core/i18n/t';

/**
 * Brigades: every brigade of an agency (BKM at first; ?agency=X), any in the timetable, in rows by line. Choosing one
 * opens its own page ({@link BrigadePage}), where its day is chosen.
 */
@Component({
  selector: 'app-brigades',
  imports: [Skeleton],
  templateUrl: './brigades.html',
  styleUrl: './brigades.scss',
})
export class BrigadesPage {
  protected readonly t = t;
  private readonly store = inject(Store);
  private readonly router = inject(Router);
  private readonly navigate = injectSetQuery();
  private readonly params = toSignal(inject(ActivatedRoute).queryParamMap, { requireSync: true });

  protected readonly list = this.store.selectSignal(brigadesFeature.selectList);
  protected readonly loadingList = this.store.selectSignal(brigadesFeature.selectLoadingList);
  protected readonly agency = computed(
    () => this.params().get('agency') ?? this.list()?.agency ?? '',
  );
  /** What's typed in the search box, to show the matching brigades. */
  protected readonly search = signal('');

  protected readonly matches = computed(() => {
    const brigades = this.list()?.brigades ?? [];
    const q = this.search().trim().toLowerCase();
    return q ? brigades.filter((b) => this.searchText(b).includes(q)) : brigades;
  });
  /** Every matching brigade, one row per line. */
  protected readonly byLine = computed(() => groupByLine(this.matches()));

  constructor() {
    // Every brigade of the agency in the URL.
    let listed: string | null | undefined;
    effect(() => {
      const agency = this.params().get('agency');
      if (agency !== listed) {
        listed = agency;
        this.store.dispatch(BrigadesActions.loadList({ agency }));
      }
    });
  }

  /** "103-05 · line 103", for the search list. */
  protected describe(b: BrigadeRef): string {
    return t('brigades.describe', { brigade: b.brigade, lines: b.lines.join(', ') });
  }

  private searchText(b: BrigadeRef): string {
    return `${b.brigade} ${b.lines.join(' ')}`.toLowerCase();
  }

  protected setAgency(agency: string): void {
    this.navigate({ agency });
  }

  /** Typed or picked in the search box: a brigade's exact name chooses it. */
  protected typed(value: string): void {
    this.search.set(value);
    const exact = (this.list()?.brigades ?? []).find(
      (b) => b.brigade.toLowerCase() === value.trim().toLowerCase(),
    );
    if (exact) this.choose(exact.brigade);
  }

  /** Opens the brigade's page, keeping the agency for the way back. */
  protected choose(brigade: string): void {
    this.search.set('');
    const agency = this.params().get('agency');
    void this.router.navigate(['/brigades', brigade], {
      queryParams: agency ? { agency } : {},
    });
  }
}

const natural = new Intl.Collator('en', { numeric: true }).compare;

/**
 * The line a brigade belongs to: the line its name starts with ("016-02" → "16"), else the first line it runs.
 * A brigade can run several lines; its name says which one is its own.
 */
export function homeLine(b: BrigadeRef): string {
  const prefix = b.brigade.split('-')[0];
  const own = b.lines.find((l) => l === prefix || l === prefix.replace(/^0+(?=.)/, ''));
  return own ?? b.lines[0] ?? prefix;
}

/** Brigades grouped by their line, lines in natural order (2, 10, 100, N1), brigades in order within each line. */
export function groupByLine(brigades: BrigadeRef[]): { line: string; brigades: BrigadeRef[] }[] {
  const groups = new Map<string, BrigadeRef[]>();
  for (const b of brigades) {
    const line = homeLine(b);
    groups.set(line, [...(groups.get(line) ?? []), b]);
  }
  return [...groups.entries()]
    .sort(([a], [b]) => natural(a, b))
    .map(([line, list]) => ({
      line,
      brigades: list.sort((a, b) => natural(a.brigade, b.brigade)),
    }));
}
