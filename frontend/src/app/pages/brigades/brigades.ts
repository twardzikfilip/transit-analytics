import { Component, ElementRef, computed, effect, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { Store } from '@ngrx/store';
import { BrigadeRef } from '../../core/models/models';
import { formatDelay, localTime } from '../../shared/format/time';
import { injectSetQuery } from '../../shared/url-query';
import { BrigadesActions } from '../../store/brigades/brigades.actions';
import { brigadesFeature } from '../../store/brigades/brigades.reducer';
import { BrigadeDayChart } from './brigade-day-chart';
import {
  KIND_LABELS,
  PROBLEMS,
  actualSpan,
  busesText,
  delayText,
  share,
  span,
  tripLabel,
} from './brigade-format';

/**
 * Brigades: pick an agency (BKM at first) and one of its brigades, any in the timetable; then a day (all in the URL:
 * ?agency=BKM&brigade=103-05&date=2026-11-18) to see its trips as scheduled and, under each, what actually ran: when,
 * by which bus, how late, and what wasn't run. The day only matters to one brigade, so only its view asks for it.
 */
@Component({
  selector: 'app-brigades',
  imports: [BrigadeDayChart],
  templateUrl: './brigades.html',
  styleUrl: './brigades.scss',
})
export class BrigadesPage {
  private readonly store = inject(Store);
  private readonly navigate = injectSetQuery();
  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly params = toSignal(inject(ActivatedRoute).queryParamMap, { requireSync: true });

  protected readonly list = this.store.selectSignal(brigadesFeature.selectList);
  protected readonly day = this.store.selectSignal(brigadesFeature.selectDay);
  protected readonly loadingList = this.store.selectSignal(brigadesFeature.selectLoadingList);
  protected readonly loadingDay = this.store.selectSignal(brigadesFeature.selectLoadingDay);

  /** The day in the URL, else today's service day (as the backend says, with the brigade's day). */
  protected readonly date = computed(
    () => this.params().get('date') ?? this.day()?.serviceDay ?? '',
  );
  protected readonly agency = computed(
    () => this.params().get('agency') ?? this.list()?.agency ?? '',
  );
  protected readonly brigade = computed(() => this.params().get('brigade'));
  /** The brigade's day shown: only the one asked for (not a previous one still on screen while it loads). */
  protected readonly shown = computed(() => {
    const d = this.day();
    return d && d.brigade === this.brigade() ? d : null;
  });
  /** What's typed in the search box, to show the matching brigades. */
  protected readonly search = signal('');
  protected readonly selected = signal<number | null>(null);

  protected readonly matches = computed(() => {
    const brigades = this.list()?.brigades ?? [];
    const q = this.search().trim().toLowerCase();
    return q ? brigades.filter((b) => this.searchText(b).includes(q)) : brigades;
  });
  /** Every matching brigade, one row per line. */
  protected readonly byLine = computed(() => groupByLine(this.matches()));

  protected readonly kindLabels = KIND_LABELS;
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
    // Every brigade of the agency in the URL.
    let listed: string | null | undefined;
    effect(() => {
      const agency = this.params().get('agency');
      if (agency !== listed) {
        listed = agency;
        this.store.dispatch(BrigadesActions.loadList({ agency }));
      }
    });
    // The chosen brigade's day.
    let loaded = '';
    effect(() => {
      const p = this.params();
      const brigade = p.get('brigade');
      const key = `${brigade}|${p.get('date')}`;
      if (key !== loaded) {
        loaded = key;
        this.selected.set(null);
        this.store.dispatch(
          brigade
            ? BrigadesActions.loadDay({ brigade, date: p.get('date') })
            : BrigadesActions.clearDay(),
        );
      }
    });
  }

  /** "103-05 · line 103", for the search list. */
  protected describe(b: BrigadeRef): string {
    return `${b.brigade} · line ${b.lines.join(', ')}`;
  }

  private searchText(b: BrigadeRef): string {
    return `${b.brigade} ${b.lines.join(' ')}`.toLowerCase();
  }

  protected setDate(date: string): void {
    if (date) this.navigate({ date });
  }

  protected setAgency(agency: string): void {
    this.navigate({ agency, brigade: null, date: null });
  }

  /** Typed or picked in the search box: a brigade's exact name chooses it. */
  protected typed(value: string): void {
    this.search.set(value);
    const exact = (this.list()?.brigades ?? []).find(
      (b) => b.brigade.toLowerCase() === value.trim().toLowerCase(),
    );
    if (exact) this.choose(exact.brigade);
  }

  /** Back to every brigade. */
  protected back(): void {
    this.navigate({ brigade: null, date: null });
  }

  protected choose(brigade: string): void {
    this.search.set('');
    this.navigate({ brigade });
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
