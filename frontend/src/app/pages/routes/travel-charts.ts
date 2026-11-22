import { Component, computed, input } from '@angular/core';
import { HourStat, LinePerformance } from '../../core/models/models';
import { clock, formatDelay } from '../../shared/format/time';
import { HourBand, HourChart, HourSeries, RefLine } from '../../shared/hour-chart/hour-chart';
import { LocalDatePipe, LocalNumberPipe } from '../../shared/i18n/locale-pipes';
import { t as tr, tn } from '../../core/i18n/t';

/** A chart's data: series, band and reference lines for the hour chart. */
interface HourChartData {
  series: HourSeries[];
  band: HourBand | null;
  refs: RefLine[];
}

/**
 * How a line actually runs, from recorded positions: travel time and speed of full runs by departure hour for the
 * base route and each longer variant, and average delay over the weekday, each with a table view.
 */
@Component({
  selector: 'app-travel-charts',
  imports: [LocalDatePipe, LocalNumberPipe, HourChart],
  templateUrl: './travel-charts.html',
  styleUrl: './travel-charts.scss',
})
export class TravelCharts {
  protected readonly tr = tr;
  protected readonly tn = tn;
  readonly performance = input<LinePerformance | null>(null);
  readonly loading = input(false);

  protected readonly clock = clock;
  /** Visible hours of the charts: the hours with data, at least 05:00–24:00. */
  protected readonly hours = computed(() => {
    const p = this.performance();
    const xs = [
      ...(p?.travelTimes.flatMap((t) => t.durations.map((d) => d.hour)) ?? []),
      ...(p?.delays.map((d) => d.minute / 60) ?? []),
    ];
    return {
      from: Math.min(5, ...xs.map(Math.floor)),
      to: Math.max(24, ...xs.map((x) => Math.floor(x) + 1)),
    };
  });
  /** Travel time and speed charts per route pattern: the base route first, then longer variants. */
  protected readonly travelCharts = computed(() =>
    (this.performance()?.travelTimes ?? []).map((t) => {
      const timeRefs: RefLine[] = [];
      if (t.scheduledShortestMin != null) {
        timeRefs.push({ label: tr('travel.schedShortest'), value: t.scheduledShortestMin });
      }
      if (t.scheduledLongestMin != null && t.scheduledLongestMin !== t.scheduledShortestMin) {
        timeRefs.push({ label: tr('travel.schedLongest'), value: t.scheduledLongestMin });
      }
      const speedRefs: RefLine[] = [];
      if (t.scheduledMaxSpeedKmh != null) {
        speedRefs.push({ label: tr('travel.schedFastest'), value: t.scheduledMaxSpeedKmh });
      }
      if (t.scheduledMinSpeedKmh != null && t.scheduledMinSpeedKmh !== t.scheduledMaxSpeedKmh) {
        speedRefs.push({ label: tr('travel.schedSlowest'), value: t.scheduledMinSpeedKmh });
      }
      return {
        t,
        title: tr(t.kind === 'main' ? 'travel.base' : 'travel.longer', { label: t.label }),
        duration: statChart(t.durations, tr('travel.timeBand'), timeRefs, true),
        speed: statChart(t.speeds, tr('travel.speedBand'), speedRefs, false),
      };
    }),
  );
  protected readonly delayChart = computed<HourChartData>(() => ({
    series: [
      {
        label: tr('travel.avgDelay'),
        color: 'var(--chart-line)',
        area: true,
        points: (this.performance()?.delays ?? []).map((d) => ({
          x: d.minute / 60 + 0.125,
          y: d.avgDelaySeconds / 60,
        })),
      },
    ],
    band: null,
    refs: [],
  }));
  protected readonly minutes = (v: number) => String(Math.round(v));
  protected readonly speed = (v: number) => (Math.round(v * 10) / 10).toFixed(1);
  /** Minutes as "m:ss" (a delay chart's values are in minutes). */
  protected readonly delayMinutes = (minutes: number) => formatDelay(minutes * 60);
}

/** Hourly min–max band with average (and median) lines, each at the middle of its hour. */
function statChart(
  stats: HourStat[],
  bandLabel: string,
  refs: RefLine[],
  withMedian: boolean,
): HourChartData {
  const series: HourSeries[] = [
    {
      label: tr('travel.average'),
      color: 'var(--chart-line)',
      points: stats.map((s) => ({ x: s.hour + 0.5, y: s.avg })),
    },
  ];
  if (withMedian) {
    series.push({
      label: tr('travel.median'),
      color: 'var(--chart-series-2)',
      points: stats.map((s) => ({ x: s.hour + 0.5, y: s.median })),
    });
  }
  return {
    series,
    band: {
      label: bandLabel,
      color: 'var(--chart-line)',
      points: stats.map((s) => ({ x: s.hour + 0.5, lo: s.min, hi: s.max })),
    },
    refs,
  };
}
