import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { Store } from '@ngrx/store';
import { map } from 'rxjs';
import {
  ProposalFrequency,
  ProposalLine,
  ProposalTrip,
  ProposalVariant,
} from '../../../core/models/models';
import { t } from '../../../core/i18n/t';
import { LocalDatePipe, LocalNumberPipe } from '../../../shared/i18n/locale-pipes';
import { VARIANT_COLORS } from '../../../shared/palette';
import { Skeleton } from '../../../shared/skeleton/skeleton';
import { injectSetQuery } from '../../../shared/url-query';
import { cityRailFeature } from '../../../store/city-rail/city-rail.reducer';
import { MapLine, MapStation, NEW_STATION_COLOR, ProposalMap } from './proposal-map';

/** The frequencies, least frequent first, as the variants come. */
export const FREQUENCIES: ProposalFrequency[] = ['1tp2h', '1tph', '2tph', '3tph'];
/** What the page opens at: a train an hour, and at most this many trains. */
export const DEFAULT_FREQUENCY: ProposalFrequency = '1tph';
export const DEFAULT_FLEET = 10;

/** A line with its colour and the figures worked out from it. */
interface ColoredLine extends ProposalLine {
  color: string;
  name: string;
  /** Stations it calls at. */
  calls: number;
  /** Average speed out, km/h. */
  speed: number;
}

/**
 * One solution of proposal no. 1 (radial: five lines from Białystok; through: lines running through it) at a
 * frequency or with at most a number of trains (?freq=2tph, or ?fleet=12: the most frequent variant that many trains
 * run; one of the two decides the other): its lines on the map, each numbered in its colour; their lengths, run times
 * and what they need; and the timetable of the line chosen (?line=S2), both ways, beside today's Polregio and PKP
 * Intercity trains on its way.
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
  protected readonly frequencies = FREQUENCIES;
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

  private readonly query = toSignal(this.route.queryParamMap, {
    initialValue: this.route.snapshot.queryParamMap,
  });

  private readonly variants = computed<ProposalVariant[]>(
    () => this.proposal()?.solutions.find((s) => s.id === this.id())?.variants ?? [],
  );

  /** The frequency asked for, or the one the fleet asked for runs, or the default. */
  protected readonly variant = computed<ProposalVariant | null>(() => {
    const variants = this.variants();
    if (!variants.length) return null;
    const byDefault = variants.find((v) => v.frequency === DEFAULT_FREQUENCY) ?? variants[0];
    const freq = this.query().get('freq');
    if (freq) return variants.find((v) => v.frequency === freq) ?? byDefault;
    const fleet = this.fleetAsked();
    if (fleet !== null)
      return [...variants].reverse().find((v) => v.vehicles <= fleet) ?? variants[0];
    return byDefault;
  });

  /** The trains at most from the URL, at least what the least frequent variant needs. */
  private readonly fleetAsked = computed(() => {
    const fleet = Number(this.query().get('fleet'));
    if (this.query().get('fleet') === null || !Number.isInteger(fleet)) return null;
    return Math.max(fleet, this.variants()[0]?.vehicles ?? 1);
  });

  /**
   * The trains at most: as asked; with a frequency asked, what it needs; at first, the default, or what the default
   * frequency needs when that is more.
   */
  protected readonly fleet = computed(() => {
    const variant = this.variant();
    if (!variant) return DEFAULT_FLEET;
    if (this.query().get('freq')) return variant.vehicles;
    return this.fleetAsked() ?? Math.max(DEFAULT_FLEET, variant.vehicles);
  });

  /** From what the least frequent variant needs to what the most frequent one does (and the fleet shown). */
  protected readonly fleets = computed(() => {
    const variants = this.variants();
    if (!variants.length) return [];
    const from = Math.min(...variants.map((v) => v.vehicles));
    const to = Math.max(...variants.map((v) => v.vehicles), this.fleet());
    return Array.from({ length: to - from + 1 }, (_, i) => from + i);
  });

  protected readonly lines = computed<ColoredLine[]>(() =>
    (this.variant()?.lines ?? []).map((line, i) => ({
      ...line,
      color: VARIANT_COLORS[i % VARIANT_COLORS.length],
      name: `${line.stations[0].name} – ${line.stations[line.stations.length - 1].name}`,
      calls: line.stations.filter((s) => s.kind !== 'passed').length,
      speed: line.runMinutes ? (line.km / line.runMinutes) * 60 : 0,
    })),
  );

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
  protected readonly chosen = computed(
    () => this.lines().find((l) => l.id === this.query().get('line')) ?? this.lines()[0] ?? null,
  );
  /** The line pointed at in the table. */
  protected readonly hovered = signal<string | null>(null);
  protected readonly highlighted = computed(() => this.hovered() ?? this.chosen()?.id ?? null);

  /** The chosen line's two ways: stations in order, and the trains on them. */
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

  protected chooseFrequency(frequency: string): void {
    this.setQuery({ freq: frequency, fleet: null });
  }

  protected chooseFleet(fleet: string): void {
    this.setQuery({ fleet: Number(fleet), freq: null });
  }

  /** A train's number, without its name, for the column's head. */
  protected short(trip: ProposalTrip): string {
    return trip.kind === 'city' ? trip.label : trip.label.split(' ')[0];
  }

  /** The column head's full text, on pointing at it. */
  protected title(trip: ProposalTrip): string {
    return trip.kind === 'city'
      ? trip.label
      : `${t('cityRail.proposal.agency.' + trip.kind)} ${trip.label}`;
  }
}
