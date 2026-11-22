import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, ParamMap } from '@angular/router';
import { Store } from '@ngrx/store';
import { timer } from 'rxjs';
import { injectSetQuery } from '../../../shared/url-query';
import { TimeSeriesChart } from '../../../shared/time-series-chart/time-series-chart';
import {
  HistoryQuery,
  PositionHistoryActions,
} from '../../../store/position-history/position-history.actions';
import { positionHistoryFeature } from '../../../store/position-history/position-history.reducer';
import { formatDelay } from '../../../shared/format/time';
import { TimeWindowPicker } from '../../../shared/time-window/time-window-picker';
import {
  POLL_MS,
  readWindow,
  refreshMs,
  windowQuery,
} from '../../../shared/time-window/time-window';
import { bandRows, formatShare, windowLabel } from './delay-bands';
import { Skeleton } from '../../../shared/skeleton/skeleton';
import { LocalDatePipe, LocalNumberPipe } from '../../../shared/i18n/locale-pipes';
import { t } from '../../../core/i18n/t';

/** The city operator (Białystok's BKM), shown unless another operator is chosen. */
export const CITY_AGENCY = 'BKM';
/** The operator filter's value for every operator. */
export const ALL_AGENCIES = 'all';

/** The operator in the URL (?agency=TUROSN, ?agency=all): null for all, the city operator when unset. */
export function readAgency(params: ParamMap): string | null {
  const agency = params.get('agency');
  if (agency === ALL_AGENCIES) return null;
  return agency || CITY_AGENCY;
}

/**
 * Recorded vehicle positions over a time window, for a line and an operator: vehicles running and mean delay (per
 * poll, i.e. per minute, for windows up to 24 h), and the delay distribution. The window, line and operator are in
 * the URL (range/from/to, route, agency); nothing else on the page uses them.
 */
@Component({
  selector: 'app-position-history',
  imports: [Skeleton, TimeSeriesChart, LocalNumberPipe, LocalDatePipe, TimeWindowPicker],
  templateUrl: './position-history.html',
  styleUrl: './position-history.scss',
})
export class PositionHistory {
  protected readonly t = t;
  private readonly store = inject(Store);
  private readonly navigate = injectSetQuery();
  private readonly params = toSignal(inject(ActivatedRoute).queryParamMap, { requireSync: true });

  protected readonly series = this.store.selectSignal(positionHistoryFeature.selectSeries);
  protected readonly routes = this.store.selectSignal(positionHistoryFeature.selectRoutes);
  protected readonly agencies = this.store.selectSignal(positionHistoryFeature.selectAgencies);
  protected readonly loadingSeries = this.store.selectSignal(
    positionHistoryFeature.selectLoadingSeries,
  );
  protected readonly query = this.store.selectSignal(positionHistoryFeature.selectQuery);
  private readonly delays = this.store.selectSignal(positionHistoryFeature.selectDelays);
  protected readonly delayBands = computed(() => bandRows(this.delays()));
  /** The window and line the loaded results cover, e.g. "Mon 16 Nov, 08:36 – 14:36 · all lines". */
  protected readonly coverage = computed(() => {
    const q = this.query();
    if (!q) return '';
    // All time: the series says where the recording starts.
    const from = q.from ?? this.series()?.from ?? null;
    const window = from
      ? windowLabel(from, q.to)
      : t('history.until', { when: windowLabel(q.to, q.to).split(' – ')[0] });
    return t('history.coverage', {
      window,
      lines: q.route ? t('history.lineLower', { line: q.route }) : t('history.allLinesLower'),
      operators: q.agency ?? t('history.allOperatorsLower'),
    });
  });

  protected readonly showTable = signal(false);

  /** URL state: the line ('' for all) and the operator (null for all). */
  protected readonly route = computed(() => this.params().get('route') ?? '');
  protected readonly agency = computed(() => readAgency(this.params()));
  /** The operators to choose from: the loaded ones, plus the chosen one so it shows before they load. */
  protected readonly agencyOptions = computed(() => {
    const chosen = this.agency();
    const list = this.agencies();
    return chosen && !list.includes(chosen) ? [chosen, ...list] : list;
  });
  protected readonly allAgencies = ALL_AGENCIES;

  /**
   * Chart inputs: the span is always the selected window (the query), so the axis follows a new window at once;
   * the points are the loaded series' within it. While another window loads, the previous one's points inside the
   * new window stay (they are real data there) and the rest are left out, so nothing from outside it is shown.
   */
  protected readonly charts = computed(() => {
    const s = this.series();
    if (!s) return null;
    const q = this.query();
    // All time: the series says where the recording starts.
    const from = q?.from ? Date.parse(q.from) : Date.parse(s.from);
    const to = q ? Date.parse(q.to) : Date.parse(s.to);
    const points = s.points
      .map((p) => ({ ...p, t: Date.parse(p.time) }))
      .filter((p) => p.t >= from && p.t < to);
    return {
      from,
      to,
      stepMs: s.stepSeconds * 1000,
      rows: points,
      vehicles: points.map((p) => ({ time: p.t, value: p.vehicles })),
      delay: points
        .filter((p) => p.meanDelaySeconds !== null)
        .map((p) => ({ time: p.t, value: p.meanDelaySeconds! / 60 })),
    };
  });

  /** Chart formatters: delay values are minutes. */
  protected readonly delayTooltip = (minutes: number) => formatDelay(minutes * 60);
  protected readonly delayAxis = (minutes: number) => t('history.minutes', { n: minutes });
  protected readonly formatDelay = formatDelay;
  protected readonly formatShare = formatShare;

  /** "every 10 s (each poll)", "every minute", or "per 10 min" for longer windows. */
  protected readonly stepLabel = computed(() => {
    const seconds = this.series()?.stepSeconds ?? 60;
    if (seconds < 60) return t('history.stepPoll', { n: seconds });
    if (seconds === 60) return t('history.stepMinute');
    if (seconds % 86_400 === 0) return t('history.stepDays', { n: seconds / 86_400 });
    if (seconds % 3_600 === 0) return t('history.stepHours', { n: seconds / 3_600 });
    return t('history.stepMinutes', { n: seconds / 60 });
  });

  constructor() {
    // Load whenever the URL changes; and as preset windows move (they end "now"), each poll for windows of up to an
    // hour, else each minute. The two are separate streams: a URL change always loads, whenever it comes.
    inject(ActivatedRoute)
      .queryParamMap.pipe(takeUntilDestroyed())
      .subscribe((params) => this.load(params));
    timer(POLL_MS, POLL_MS)
      .pipe(takeUntilDestroyed())
      .subscribe((tick) => {
        const params = this.params();
        const w = this.resolve(new Date(), params);
        if (!w.preset) return; // custom windows don't move
        if ((tick + 1) % (refreshMs(w) / POLL_MS) === 0) this.load(params);
      });
    this.store.dispatch(PositionHistoryActions.loadAgencies());
  }

  private load(params: ParamMap): void {
    const query: HistoryQuery = {
      ...windowQuery(this.resolve(new Date(), params)),
      route: params.get('route') || null,
      agency: readAgency(params),
    };
    this.store.dispatch(PositionHistoryActions.load({ query }));
  }

  private resolve(now: Date, params = this.params()) {
    return readWindow(params, '24h', now);
  }

  protected setRoute(route: string): void {
    this.navigate({ route: route || null });
  }

  /** Another operator has other lines, so the line filter is cleared. */
  protected setAgency(agency: string): void {
    this.navigate({ agency: agency === CITY_AGENCY ? null : agency, route: null });
  }
}
