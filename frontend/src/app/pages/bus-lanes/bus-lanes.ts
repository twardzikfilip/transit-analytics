import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, ElementRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { Store } from '@ngrx/store';
import { distinctUntilChanged, map } from 'rxjs';
import { BusLaneApi } from '../../core/api/api.services';
import { LaneStretchFigures } from '../../core/models/models';
import { HourChart, HourSeries } from '../../shared/hour-chart/hour-chart';
import { TimeWindowPicker } from '../../shared/time-window/time-window-picker';
import {
  TimeWindow,
  readWindow,
  sameQuery,
  windowQuery,
} from '../../shared/time-window/time-window';
import { BusLanesActions } from '../../store/bus-lanes/bus-lanes.actions';
import { busLanesFeature } from '../../store/bus-lanes/bus-lanes.reducer';
import { BusLaneMap } from './bus-lane-map';
import { LaneDirectionMap } from './lane-direction-map';
import { delayText, headingText, linesText, slowdownText, speedText } from './lane-format';

export const DEFAULT_PRESET = '1w';

/** Rows of the table shown before "Show all". */
export const FIRST_ROWS = 25;

export type StretchSort = 'driven' | 'slowest' | 'delay' | 'slowdown';

const SORTS: Record<StretchSort, (a: LaneStretchFigures, b: LaneStretchFigures) => number> = {
  driven: (a, b) => b.figures.km - a.figures.km,
  slowest: (a, b) => (a.figures.speed ?? Infinity) - (b.figures.speed ?? Infinity),
  delay: (a, b) => (b.figures.delayPerKm ?? -Infinity) - (a.figures.delayPerKm ?? -Infinity),
  slowdown: (a, b) => (b.figures.peakSlowdown ?? -Infinity) - (a.figures.peakSlowdown ?? -Infinity),
};

/**
 * Bus lanes: where they are (OpenStreetMap, compared with the city's figure), and how fast buses go on them and how
 * much delay they gain, against the same streets the other way and the rest of the city. The window is in the URL.
 */
@Component({
  selector: 'app-bus-lanes',
  imports: [DatePipe, DecimalPipe, BusLaneMap, HourChart, LaneDirectionMap, TimeWindowPicker],
  templateUrl: './bus-lanes.html',
  styleUrl: './bus-lanes.scss',
})
export class BusLanesPage {
  private readonly store = inject(Store);
  private readonly host = inject(ElementRef<HTMLElement>);

  protected readonly report = this.store.selectSignal(busLanesFeature.selectReport);
  protected readonly loading = this.store.selectSignal(busLanesFeature.selectLoading);
  protected readonly error = this.store.selectSignal(busLanesFeature.selectError);
  /** The lanes themselves; fixed data, fetched once. */
  protected readonly lanes = toSignal(inject(BusLaneApi).lanes(), { initialValue: null });

  protected readonly defaultPreset = DEFAULT_PRESET;
  private readonly window$ = inject(ActivatedRoute).queryParamMap.pipe(
    map((p): { window: TimeWindow } => ({ window: readWindow(p, DEFAULT_PRESET) })),
    distinctUntilChanged(sameQuery),
  );

  protected readonly selected = signal<number | null>(null);
  protected readonly sort = signal<StretchSort>('driven');
  protected readonly showAll = signal(false);
  protected readonly firstRows = FIRST_ROWS;

  protected readonly figuresById = computed(
    () => new Map((this.report()?.stretches ?? []).map((s) => [s.id, s])),
  );
  protected readonly sorted = computed(() =>
    [...(this.report()?.stretches ?? [])].sort(SORTS[this.sort()]),
  );
  protected readonly rows = computed(() =>
    this.showAll() ? this.sorted() : this.sorted().slice(0, FIRST_ROWS),
  );

  /** The three groups compared, in table order. */
  protected readonly groups = computed(() => {
    const r = this.report();
    if (!r) return [];
    return [
      { label: 'On bus lanes', note: 'buses on a bus lane', f: r.onLanes },
      {
        label: 'Same streets, other way',
        note: 'the streets with bus lanes, in the direction without one',
        f: r.sameStreetsWithoutLane,
      },
      { label: 'Elsewhere in the city', note: 'every other street', f: r.elsewhere },
    ];
  });

  /** OpenStreetMap's length against the city's figure, % apart. */
  protected readonly lengthGap = computed(() => {
    const l = this.lanes();
    if (!l?.official || l.official.km <= 0) return null;
    return ((l.laneKm - l.official.km) / l.official.km) * 100;
  });

  protected readonly speedSeries = computed((): HourSeries[] => {
    const hours = this.report()?.hours ?? [];
    return [
      {
        label: 'On bus lanes',
        color: 'var(--bus-lane)',
        points: hours
          .filter((h) => h.laneSpeed !== null)
          .map((h) => ({ x: h.hour + 0.5, y: h.laneSpeed! })),
      },
      {
        label: 'Elsewhere',
        color: 'var(--chart-line)',
        points: hours
          .filter((h) => h.otherSpeed !== null)
          .map((h) => ({ x: h.hour + 0.5, y: h.otherSpeed! })),
      },
    ];
  });

  protected readonly delaySeries = computed((): HourSeries[] => {
    const hours = this.report()?.hours ?? [];
    return [
      {
        label: 'On bus lanes',
        color: 'var(--bus-lane)',
        points: hours
          .filter((h) => h.laneDelayPerKm !== null)
          .map((h) => ({ x: h.hour + 0.5, y: h.laneDelayPerKm! })),
      },
      {
        label: 'Elsewhere',
        color: 'var(--chart-line)',
        points: hours
          .filter((h) => h.otherDelayPerKm !== null)
          .map((h) => ({ x: h.hour + 0.5, y: h.otherDelayPerKm! })),
      },
    ];
  });

  protected readonly speedText = speedText;
  protected readonly delayText = delayText;
  protected readonly slowdownText = slowdownText;
  protected readonly headingText = headingText;
  protected readonly linesText = linesText;
  protected readonly kmh = (v: number) => `${v.toFixed(1)} km/h`;
  protected readonly perKm = (v: number) => delayText(v);

  constructor() {
    this.window$.pipe(takeUntilDestroyed()).subscribe(({ window }) => {
      this.selected.set(null);
      this.showAll.set(false);
      this.store.dispatch(BusLanesActions.load({ window: windowQuery(window) }));
    });
  }

  protected setSort(sort: string): void {
    this.sort.set(sort as StretchSort);
  }

  /** Whether the lane is slower than the same street the other way, by more than 1 km/h. */
  protected slowerThanOpposite(s: LaneStretchFigures): boolean {
    return (
      s.figures.speed !== null &&
      s.opposite?.speed !== null &&
      s.opposite?.speed !== undefined &&
      s.figures.speed < s.opposite.speed - 1
    );
  }

  /** Chosen in the table: the map moves to it. */
  protected choose(id: number): void {
    this.selected.set(this.selected() === id ? null : id);
  }

  /** Chosen on the map: the table shows its row. */
  protected chooseOnMap(id: number): void {
    const index = this.sorted().findIndex((s) => s.id === id);
    if (index >= FIRST_ROWS) this.showAll.set(true);
    this.selected.set(id);
    setTimeout(() =>
      (this.host.nativeElement as HTMLElement)
        .querySelector(`tr[data-id="${id}"]`)
        ?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' }),
    );
  }
}
