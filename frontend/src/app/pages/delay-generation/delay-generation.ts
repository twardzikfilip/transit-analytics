import { DatePipe } from '@angular/common';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { Store } from '@ngrx/store';
import { injectSetQuery } from '../../shared/url-query';
import { distinctUntilChanged, map } from 'rxjs';
import {
  DelayExample,
  DelayGenerationReport,
  DelayLineSummary,
  DelayStretch,
} from '../../core/models/models';
import { localTime } from '../../shared/format/time';
import { DelayGenerationActions } from '../../store/delay-generation/delay-generation.actions';
import { delayGenerationFeature } from '../../store/delay-generation/delay-generation.reducer';
import { TimeWindowPicker } from '../../shared/time-window/time-window-picker';
import { readWindow, sameQuery, windowQuery } from '../../shared/time-window/time-window';
import { changeClass, delayText, minutesPerDay, signedChange } from './delay-scale';
import { LineHeatmap } from './line-heatmap';
import { MIN_MAP_RUNS, StretchMap } from './stretch-map';
import { TripProfile } from './trip-profile';
import { percent } from '../../shared/format/number';

/** Stretches listed where delay is created, and where it is recovered. */
export const CREATED_ROWS = 15;
export const RECOVERED_ROWS = 10;

/** A stretch with its figures in one part of the day. */
export interface BandStretch {
  stretch: DelayStretch;
  runs: number;
  meanSeconds: number;
  /** Bus-minutes of delay per recorded day. */
  minutesPerDay: number;
}

/** The stretches measured in a part of the day, with their per-day totals. */
export function bandStretches(report: DelayGenerationReport, band: number): BandStretch[] {
  return report.stretches
    .filter((s) => s.runs[band] >= MIN_MAP_RUNS && s.meanSeconds[band] !== null)
    .map((s) => ({
      stretch: s,
      runs: s.runs[band],
      meanSeconds: s.meanSeconds[band]!,
      minutesPerDay: minutesPerDay(s.meanSeconds[band]!, s.runs[band], report.days),
    }));
}

export const DEFAULT_PRESET = '1w';

/**
 * Delay generation: where on the network buses gain delay between stops (and where they recover it), on a map and
 * in tables, then for a chosen line, when and on which stretches, with example runs and how their delay built up.
 * The window, part of the day and line are in the URL.
 */
@Component({
  selector: 'app-delay-generation',
  imports: [DatePipe, LineHeatmap, StretchMap, TripProfile, TimeWindowPicker],
  templateUrl: './delay-generation.html',
  styleUrl: './delay-generation.scss',
})
export class DelayGeneration {
  private readonly store = inject(Store);
  private readonly navigate = injectSetQuery();

  protected readonly report = this.store.selectSignal(delayGenerationFeature.selectReport);
  /** A share in whole percent, "42%". */
  protected readonly wholePercent = (share: number) => percent(share, { digits: 0 });
  protected readonly loading = this.store.selectSignal(delayGenerationFeature.selectLoading);
  protected readonly lineReport = this.store.selectSignal(delayGenerationFeature.selectLine);
  protected readonly lineLoading = this.store.selectSignal(
    delayGenerationFeature.selectLineLoading,
  );

  protected readonly defaultPreset = DEFAULT_PRESET;
  private readonly params = inject(ActivatedRoute).queryParamMap;
  private readonly window$ = this.params.pipe(
    map((p) => readWindow(p, DEFAULT_PRESET)),
    distinctUntilChanged((a, b) => sameQuery({ window: a }, { window: b })),
  );
  private readonly line$ = this.params.pipe(
    map((p) => p.get('line') || null),
    distinctUntilChanged(),
  );
  protected readonly window = toSignal(this.window$, { requireSync: true });
  protected readonly line = toSignal(this.line$, { requireSync: true });
  private readonly bandKey = toSignal(this.params.pipe(map((p) => p.get('part'))), {
    requireSync: true,
  });
  /** The part of the day (index in the report's bands; 0 is the whole day). */
  protected readonly band = computed(() => {
    const i = this.report()?.bands.findIndex((b) => b.key === this.bandKey()) ?? -1;
    return Math.max(0, i);
  });

  protected readonly measured = computed(() => {
    const r = this.report();
    return r ? bandStretches(r, this.band()) : [];
  });
  protected readonly created = computed(() =>
    this.measured()
      .filter((s) => s.meanSeconds > 0)
      .sort((a, b) => b.minutesPerDay - a.minutesPerDay)
      .slice(0, CREATED_ROWS),
  );
  protected readonly recovered = computed(() =>
    this.measured()
      .filter((s) => s.meanSeconds < 0)
      .sort((a, b) => a.minutesPerDay - b.minutesPerDay)
      .slice(0, RECOVERED_ROWS),
  );
  /** Lines by the delay their buses gain altogether, the most first. */
  protected readonly lines = computed(() =>
    [...(this.report()?.lines ?? [])].sort(
      (a, b) => b.generatedMinutesPerDay - a.generatedMinutesPerDay,
    ),
  );

  // The chosen line: a direction, a stretch in it, and an example run.
  protected readonly directionIndex = signal(0);
  protected readonly stretchIndex = signal<number | null>(null);
  protected readonly exampleId = signal<number | null>(null);
  protected readonly direction = computed(
    () => this.lineReport()?.directions[this.directionIndex()] ?? null,
  );
  protected readonly stretch = computed(() => {
    const i = this.stretchIndex();
    return i === null ? null : (this.direction()?.stretches[i] ?? null);
  });
  /** The chosen stretch's runs that gained the most there; else the direction's runs that gained the most. */
  protected readonly examples = computed((): DelayExample[] => {
    const report = this.lineReport();
    const ids = this.stretch()?.exampleIds ?? this.direction()?.exampleIds ?? [];
    return ids.map((id) => report?.examples.find((e) => e.id === id)).filter((e) => !!e);
  });
  protected readonly example = computed(
    () => this.examples().find((e) => e.id === this.exampleId()) ?? this.examples()[0] ?? null,
  );

  protected readonly signed = signedChange;
  protected readonly delayText = delayText;
  protected readonly time = localTime;
  protected readonly createdRows = CREATED_ROWS;

  constructor() {
    this.window$.pipe(takeUntilDestroyed()).subscribe((window) => {
      this.store.dispatch(DelayGenerationActions.load({ window: windowQuery(window) }));
    });
    // A line (or another window for it) loads the line; no line clears it.
    effect(() => {
      const window = windowQuery(this.window());
      const line = this.line();
      untracked(() => {
        this.directionIndex.set(0);
        this.stretchIndex.set(null);
        this.exampleId.set(null);
        this.store.dispatch(
          line
            ? DelayGenerationActions.loadLine({ window, line })
            : DelayGenerationActions.clearLine(),
        );
      });
    });
  }

  protected setBand(key: string): void {
    this.navigate({ part: key === 'all' ? null : key });
  }

  protected chooseLine(line: string | null): void {
    this.navigate({ line });
    if (line) {
      // The line's section shows straight away (loading): bring it into view.
      setTimeout(() =>
        document.getElementById('line-detail')?.scrollIntoView?.({ behavior: 'smooth' }),
      );
    }
  }

  protected chooseDirection(i: number): void {
    this.directionIndex.set(i);
    this.stretchIndex.set(null);
    this.exampleId.set(null);
  }

  protected chooseStretch(i: number): void {
    this.stretchIndex.set(this.stretchIndex() === i ? null : i);
    this.exampleId.set(null);
  }

  protected color(seconds: number): string {
    return changeClass(seconds).color;
  }

  protected dark(seconds: number): boolean {
    return changeClass(seconds).dark;
  }

  protected worst(l: DelayLineSummary): string {
    return l.worstFrom ? `${l.worstFrom} → ${l.worstTo}` : '—';
  }
}
