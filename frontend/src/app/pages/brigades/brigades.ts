import { Component, computed, effect, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { BrigadeAgency, BrigadeRef } from '../../core/models/models';
import { injectSetQuery } from '../../shared/url-query';
import { BrigadesActions } from '../../store/brigades/brigades.actions';
import { brigadesFeature } from '../../store/brigades/brigades.reducer';
import { Skeleton } from '../../shared/skeleton/skeleton';
import { t } from '../../core/i18n/t';
import { RouteBadge } from '../../shared/route-kind/route-kinds';

/**
 * Brigades: every brigade of an operator (BKM at first; ?agency=X: the city's agency, or another operator's feed),
 * any in the timetable or learnt from the recordings, in rows by route. Choosing one
 * opens its own page ({@link BrigadePage}), where its day is chosen.
 */
@Component({
  selector: 'app-brigades',
  imports: [RouteBadge, Skeleton],
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
  /** The operator listed: as the answer has it (a former agency key in the URL becomes its operator's). */
  protected readonly agency = computed(
    () => this.list()?.agency ?? this.params().get('agency') ?? '',
  );
  /** What's typed in the search box, to show the matching brigades. */
  protected readonly search = signal('');

  protected readonly matches = computed(() => {
    const brigades = this.list()?.brigades ?? [];
    const q = this.search().trim().toLowerCase();
    return q ? brigades.filter((b) => this.searchText(b).includes(q)) : brigades;
  });
  /** Every matching brigade, one row per route. */
  protected readonly byRoute = computed(() => groupByRoute(this.matches()));

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

  /**
   * An operator's name: another operator's timetable by its short name (Wschód Express), as its agencies are the
   * municipalities ordering its routes; else as the timetable names it.
   */
  protected operatorName(a: BrigadeAgency): string {
    const key = `timetable.feeds.${a.id}`;
    const name = t(key);
    return name === key ? a.name : name;
  }

  /** "103-05 · route 103", for the search list. */
  protected describe(b: BrigadeRef): string {
    return t('brigades.describe', { brigade: b.brigade, routes: b.routes.join(', ') });
  }

  private searchText(b: BrigadeRef): string {
    return `${b.brigade} ${b.routes.join(' ')}`.toLowerCase();
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
 * The route a brigade belongs to: the route its name starts with ("016-02" → "16"), else the first route it runs.
 * A brigade can run several routes; its name says which one is its own.
 */
export function homeRoute(b: BrigadeRef): string {
  const prefix = b.brigade.split('-')[0];
  const own = b.routes.find((l) => l === prefix || l === prefix.replace(/^0+(?=.)/, ''));
  return own ?? b.routes[0] ?? prefix;
}

/** Brigades grouped by their route, routes in natural order (2, 10, 100, N1), brigades in order within each route. */
export function groupByRoute(brigades: BrigadeRef[]): { route: string; brigades: BrigadeRef[] }[] {
  const groups = new Map<string, BrigadeRef[]>();
  for (const b of brigades) {
    const route = homeRoute(b);
    groups.set(route, [...(groups.get(route) ?? []), b]);
  }
  return [...groups.entries()]
    .sort(([a], [b]) => natural(a, b))
    .map(([route, list]) => ({
      route,
      brigades: list.sort((a, b) => natural(a.brigade, b.brigade)),
    }));
}
