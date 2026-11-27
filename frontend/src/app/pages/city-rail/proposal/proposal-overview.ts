import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Store } from '@ngrx/store';
import { t } from '../../../core/i18n/t';
import { LocalDatePipe, LocalNumberPipe } from '../../../shared/i18n/locale-pipes';
import { VARIANT_COLORS } from '../../../shared/palette';
import { Skeleton } from '../../../shared/skeleton/skeleton';
import { cityRailFeature } from '../../../store/city-rail/city-rail.reducer';
import { MapLine, MapStation, NEW_STATION_COLOR, ProposalMap } from './proposal-map';

/**
 * Proposal no. 1, its overview: the five railways on the map (the one to Kuriany, unused today, dashed) with their
 * stations, the new ones named; then each railway's stations, what every timetable keeps to, and the two solutions.
 */
@Component({
  selector: 'app-proposal-overview',
  imports: [LocalDatePipe, LocalNumberPipe, ProposalMap, RouterLink, Skeleton],
  templateUrl: './proposal-overview.html',
  styleUrl: './proposal-pages.scss',
})
export class ProposalOverview {
  protected readonly t = t;
  protected readonly newColor = NEW_STATION_COLOR;
  private readonly store = inject(Store);
  protected readonly proposal = this.store.selectSignal(cityRailFeature.selectProposal);
  protected readonly loading = this.store.selectSignal(cityRailFeature.selectProposalLoading);
  private readonly report = this.store.selectSignal(cityRailFeature.selectReport);
  protected readonly railways = computed(() => this.report()?.railways ?? []);

  /** The railways, each in its colour, named from Białystok to its end. */
  protected readonly corridors = computed(() =>
    (this.proposal()?.corridors ?? []).map((c, i) => {
      const stations = c.stations;
      return {
        ...c,
        color: VARIANT_COLORS[i % VARIANT_COLORS.length],
        name: `${stations[0].name} – ${stations[stations.length - 1].name}`,
        km: stations[stations.length - 1].km,
        fresh: stations.filter((s) => s.kind === 'new').length,
        spacing: stations[stations.length - 1].km / (stations.length - 1),
      };
    }),
  );

  protected readonly lines = computed<MapLine[]>(() =>
    this.corridors().map((c) => ({
      id: c.id,
      color: c.color,
      points: c.points,
      name: t('cityRail.proposal.corridorName', { name: c.name, ref: c.ref }),
      badges: [],
      dashed: !c.served,
    })),
  );

  /** Every station once (Białystok and Zielone Wzgórza are on several railways). */
  protected readonly stations = computed<MapStation[]>(() => {
    const all = new Map<string, MapStation>();
    for (const c of this.proposal()?.corridors ?? [])
      for (const s of c.stations) all.set(s.name, s);
    return [...all.values()];
  });

  /** The rules, for the text. */
  protected readonly rules = computed(() => this.proposal()?.rules);
}
