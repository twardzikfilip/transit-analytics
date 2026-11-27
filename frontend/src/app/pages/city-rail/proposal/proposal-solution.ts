import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { Store } from '@ngrx/store';
import { map } from 'rxjs';
import { ProposalLine } from '../../../core/models/models';
import { t } from '../../../core/i18n/t';
import { LocalDatePipe, LocalNumberPipe } from '../../../shared/i18n/locale-pipes';
import { VARIANT_COLORS } from '../../../shared/palette';
import { Skeleton } from '../../../shared/skeleton/skeleton';
import { injectSetQuery } from '../../../shared/url-query';
import { cityRailFeature } from '../../../store/city-rail/city-rail.reducer';
import { MapLine, MapStation, NEW_STATION_COLOR, ProposalMap } from './proposal-map';

/** A line with its colour and the figures worked out from it. */
interface ColoredLine extends ProposalLine {
  color: string;
  name: string;
  /** Average speed out, km/h. */
  speed: number;
}

/**
 * One solution of proposal no. 1 (radial: five lines from Białystok; through: lines running through it): its lines
 * on the map, each numbered in its colour; their lengths, run times and what they need; and the timetable of the
 * line chosen (?line=S2), both ways, under the map.
 */
@Component({
  selector: 'app-proposal-solution',
  imports: [LocalDatePipe, LocalNumberPipe, ProposalMap, Skeleton],
  templateUrl: './proposal-solution.html',
  styleUrls: ['./proposal-pages.scss', './proposal-solution.scss'],
})
export class ProposalSolutionPage {
  protected readonly t = t;
  protected readonly newColor = NEW_STATION_COLOR;
  private readonly store = inject(Store);
  private readonly route = inject(ActivatedRoute);
  private readonly setQuery = injectSetQuery();

  protected readonly proposal = this.store.selectSignal(cityRailFeature.selectProposal);
  protected readonly loading = this.store.selectSignal(cityRailFeature.selectProposalLoading);
  private readonly report = this.store.selectSignal(cityRailFeature.selectReport);
  protected readonly railways = computed(() => this.report()?.railways ?? []);

  /** radial or through, from the route. */
  protected readonly id = toSignal(this.route.data.pipe(map((d) => d['solution'] as string)), {
    initialValue: this.route.snapshot.data['solution'] as string,
  });

  protected readonly lines = computed<ColoredLine[]>(() => {
    const solution = this.proposal()?.solutions.find((s) => s.id === this.id());
    return (solution?.lines ?? []).map((line, i) => ({
      ...line,
      color: VARIANT_COLORS[i % VARIANT_COLORS.length],
      name: `${line.stations[0].name} – ${line.stations[line.stations.length - 1].name}`,
      speed: line.runMinutes ? (line.km / line.runMinutes) * 60 : 0,
    }));
  });

  protected readonly mapLines = computed<MapLine[]>(() => {
    const hub = this.hub();
    return this.lines().map((line) => ({
      id: line.id,
      color: line.color,
      points: line.points,
      name: `${line.id}: ${line.name}`,
      // The number at each end that isn't Białystok.
      badges: [
        ...(line.stations[0].name !== hub ? [line.points[0]] : []),
        ...(line.stations[line.stations.length - 1].name !== hub
          ? [line.points[line.points.length - 1]]
          : []),
      ],
    }));
  });

  /** Where all the railways start. */
  private readonly hub = computed(() => this.proposal()?.corridors[0]?.stations[0].name ?? '');

  protected readonly stations = computed<MapStation[]>(() => {
    const all = new Map<string, MapStation>();
    for (const c of this.proposal()?.corridors ?? [])
      for (const s of c.stations) all.set(s.name, s);
    return [...all.values()];
  });

  /** The line chosen, from the URL; the first when none is (or one of another solution is). */
  private readonly chosenId = toSignal(this.route.queryParamMap.pipe(map((p) => p.get('line'))), {
    initialValue: null,
  });
  protected readonly chosen = computed(
    () => this.lines().find((l) => l.id === this.chosenId()) ?? this.lines()[0] ?? null,
  );
  /** The line pointed at in the table. */
  protected readonly hovered = signal<string | null>(null);
  protected readonly highlighted = computed(() => this.hovered() ?? this.chosen()?.id ?? null);

  /** The chosen line's two ways: stations in order, and its trains. */
  protected readonly ways = computed(() => {
    const line = this.chosen();
    if (!line) return [];
    const back = [...line.stations].reverse();
    return [
      { key: 'out', stations: line.stations, trips: line.out },
      { key: 'back', stations: back, trips: line.back },
    ].map((w) => ({
      ...w,
      title: t('cityRail.proposal.towards', { to: w.stations[w.stations.length - 1].name }),
    }));
  });

  protected choose(id: string): void {
    this.setQuery({ line: id });
  }
}
