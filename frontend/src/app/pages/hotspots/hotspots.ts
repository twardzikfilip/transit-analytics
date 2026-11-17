import { DatePipe } from '@angular/common';
import { Component, ElementRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { distinctUntilChanged, map } from 'rxjs';
import { StatsWindow } from '../../core/models/models';
import { HotspotsActions } from '../../store/hotspots/hotspots.actions';
import { hotspotsFeature } from '../../store/hotspots/hotspots.reducer';
import { WINDOWS, parseWindow } from '../vehicle-stats/vehicle-stats';
import { approachText, hourText, linesText, lostTime, nearText, waitText } from './hotspot-format';
import { HotspotMap } from './hotspot-map';

/** Rows of the table shown before "Show all". */
export const FIRST_ROWS = 25;

/**
 * Stop hotspots: places where buses stand still away from stops (traffic lights, junctions, congestion), on a map
 * and in a table, the most time lost first. The time window and line are in the URL.
 */
@Component({
  selector: 'app-hotspots',
  imports: [DatePipe, HotspotMap],
  templateUrl: './hotspots.html',
  styleUrl: './hotspots.scss',
})
export class Hotspots {
  private readonly store = inject(Store);
  private readonly router = inject(Router);
  private readonly host = inject(ElementRef<HTMLElement>);

  protected readonly report = this.store.selectSignal(hotspotsFeature.selectReport);
  protected readonly loading = this.store.selectSignal(hotspotsFeature.selectLoading);
  protected readonly error = this.store.selectSignal(hotspotsFeature.selectError);

  protected readonly windows = WINDOWS;
  /** The window and line in the URL (?window=month&line=21); a change loads their report. */
  private readonly query$ = inject(ActivatedRoute).queryParamMap.pipe(
    map((p) => ({ window: parseWindow(p.get('window')), line: p.get('line') || null })),
    distinctUntilChanged((a, b) => a.window === b.window && a.line === b.line),
  );
  private readonly query = toSignal(this.query$, { requireSync: true });
  protected readonly window = computed(() => this.query().window);
  protected readonly line = computed(() => this.query().line);
  /** The window's lines, and the chosen one even without standstills in it, so it stays selected. */
  protected readonly lineOptions = computed(() => {
    const lines = this.report()?.lines ?? [];
    const line = this.line();
    return line && !lines.includes(line) ? [line, ...lines] : lines;
  });

  /** The hotspot chosen on the map or in the table. */
  protected readonly selected = signal<number | null>(null);
  protected readonly showAll = signal(false);
  protected readonly rows = computed(() => {
    const hotspots = this.report()?.hotspots ?? [];
    return this.showAll() ? hotspots : hotspots.slice(0, FIRST_ROWS);
  });
  protected readonly firstRows = FIRST_ROWS;
  /** Share of the standstill time that is in the hotspots. */
  protected readonly inHotspots = computed(() => {
    const r = this.report();
    return r && r.standstillHours > 0 ? Math.round((r.hotspotHours / r.standstillHours) * 100) : 0;
  });

  protected readonly lostTime = lostTime;
  protected readonly waitText = waitText;
  protected readonly nearText = nearText;
  protected readonly approachText = approachText;
  protected readonly linesText = linesText;
  protected readonly hourText = hourText;

  constructor() {
    this.query$.pipe(takeUntilDestroyed()).subscribe(({ window, line }) => {
      this.selected.set(null);
      this.showAll.set(false);
      this.store.dispatch(HotspotsActions.load({ window, line }));
    });
  }

  protected setWindow(window: StatsWindow): void {
    this.navigate({ window: window === 'week' ? null : window });
  }

  protected setLine(line: string): void {
    this.navigate({ line: line || null });
  }

  /** Chosen in the table: the map moves to it. */
  protected choose(id: number): void {
    this.selected.set(this.selected() === id ? null : id);
  }

  /** Chosen on the map: the table shows its row. */
  protected chooseOnMap(id: number): void {
    const index = this.report()?.hotspots.findIndex((h) => h.id === id) ?? -1;
    if (index >= FIRST_ROWS) this.showAll.set(true);
    this.selected.set(id);
    setTimeout(() =>
      (this.host.nativeElement as HTMLElement)
        .querySelector(`tr[data-id="${id}"]`)
        ?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' }),
    );
  }

  private navigate(queryParams: Record<string, string | null>): void {
    void this.router.navigate([], { queryParams, queryParamsHandling: 'merge' });
  }
}
