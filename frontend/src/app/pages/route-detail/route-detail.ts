import { DatePipe, DecimalPipe } from '@angular/common';
import {
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { Store } from '@ngrx/store';
import * as L from 'leaflet';
import {
  HourStat,
  LineDetail,
  LineVariant,
  RouteSubtype,
  VariantKind,
} from '../../core/models/models';
import { RouteDetailActions } from '../../store/route-detail/route-detail.actions';
import { routeDetailFeature } from '../../store/route-detail/route-detail.reducer';
import { BrigadeTimeline } from './brigade-timeline';
import { EndStops } from './end-stops';
import { LineVehiclesSection } from './line-vehicles';
import { SUBTYPES, clock, duration, groupLines, subtypeLabel, variantKindLabel } from './format';
import { HourBand, HourChart, HourSeries, RefLine } from './hour-chart';
import { SegmentChart } from './segment-chart';

const BIALYSTOK: L.LatLngExpression = [53.13, 23.16];

/** A chart's data: series, band and reference lines for the hour chart. */
interface HourChartData {
  series: HourSeries[];
  band: HourBand | null;
  refs: RefLine[];
}

/**
 * Routes in detail: pick a line to see it on the map with its variants, its timetable figures (lengths, travel
 * times, speeds, zone 2, brigades) and how it actually runs, measured from recorded vehicle positions.
 */
@Component({
  selector: 'app-route-detail',
  imports: [
    DatePipe,
    DecimalPipe,
    HourChart,
    SegmentChart,
    BrigadeTimeline,
    EndStops,
    LineVehiclesSection,
  ],
  templateUrl: './route-detail.html',
  styleUrl: './route-detail.scss',
})
export class RouteDetail {
  private readonly store = inject(Store);
  private readonly router = inject(Router);
  private readonly params = toSignal(inject(ActivatedRoute).queryParamMap, { requireSync: true });
  private readonly mapEl = viewChild.required<ElementRef<HTMLDivElement>>('map');

  protected readonly lines = this.store.selectSignal(routeDetailFeature.selectLines);
  protected readonly detail = this.store.selectSignal(routeDetailFeature.selectDetail);
  protected readonly performance = this.store.selectSignal(routeDetailFeature.selectPerformance);
  protected readonly loadingDetail = this.store.selectSignal(
    routeDetailFeature.selectLoadingDetail,
  );
  protected readonly loadingPerformance = this.store.selectSignal(
    routeDetailFeature.selectLoadingPerformance,
  );
  protected readonly error = this.store.selectSignal(routeDetailFeature.selectError);

  protected readonly groups = computed(() => groupLines(this.lines()));
  /** The line in the URL (?line=103), else the first line. */
  protected readonly selected = computed(
    () => this.params().get('line') ?? this.lines()[0]?.line ?? null,
  );

  protected readonly clock = clock;
  protected readonly duration = duration;
  protected readonly subtypeLabel = subtypeLabel;
  protected readonly kindLabel = variantKindLabel;
  /** Short kind label for the tile lists. */
  protected readonly shortKind = (kind: VariantKind) =>
    ({ main: 'Base', longer: 'Longer', shorter: 'Shorter', depot: 'Depot' })[kind];

  protected readonly mains = computed(
    () => this.detail()?.variants.filter((v) => v.kind === 'main') ?? [],
  );
  protected readonly others = computed(
    () => this.detail()?.variants.filter((v) => v.kind !== 'main') ?? [],
  );
  protected readonly trips = computed(
    () => this.detail()?.variants.reduce((n, v) => n + v.tripsPerDay, 0) ?? 0,
  );
  /** "Piastowska ⇄ Choroszcz/Cmentarz": the two termini of the base route. */
  protected readonly termini = computed(() => {
    const m = this.mains()[0];
    return m ? `${m.firstStop} ⇄ ${m.lastStop}` : '';
  });

  /** Zone 2 entries of the base route first, then of longer variants. */
  protected readonly zone2 = computed(() => {
    const zones = this.detail()?.zone2 ?? [];
    return {
      base: zones.filter((z) => z.kind === 'main'),
      longer: zones.filter((z) => z.kind !== 'main'),
    };
  });

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
        timeRefs.push({ label: 'Scheduled shortest', value: t.scheduledShortestMin });
      }
      if (t.scheduledLongestMin != null && t.scheduledLongestMin !== t.scheduledShortestMin) {
        timeRefs.push({ label: 'Scheduled longest', value: t.scheduledLongestMin });
      }
      const speedRefs: RefLine[] = [];
      if (t.scheduledMaxSpeedKmh != null) {
        speedRefs.push({ label: 'Scheduled fastest', value: t.scheduledMaxSpeedKmh });
      }
      if (t.scheduledMinSpeedKmh != null && t.scheduledMinSpeedKmh !== t.scheduledMaxSpeedKmh) {
        speedRefs.push({ label: 'Scheduled slowest', value: t.scheduledMinSpeedKmh });
      }
      return {
        t,
        title: `${t.kind === 'main' ? 'Base route' : 'Longer variant'}: ${t.label}`,
        duration: statChart(t.durations, 'Shortest–longest run', timeRefs, true),
        speed: statChart(t.speeds, 'Slowest–fastest run', speedRefs, false),
      };
    }),
  );

  protected readonly delayChart = computed<HourChartData>(() => ({
    series: [
      {
        label: 'Average delay',
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
  protected readonly delayMinutes = (v: number) => {
    const s = Math.round(v * 60);
    const sign = s < 0 ? '−' : '';
    return `${sign}${Math.floor(Math.abs(s) / 60)}:${String(Math.abs(s) % 60).padStart(2, '0')}`;
  };

  private map?: L.Map;
  private readonly mapReady = signal(false);
  private layers?: L.LayerGroup;

  constructor() {
    this.store.dispatch(RouteDetailActions.loadLines());

    // Load the selected line once per change.
    let loaded: string | null = null;
    effect(() => {
      const line = this.selected();
      if (line && line !== loaded) {
        loaded = line;
        this.store.dispatch(RouteDetailActions.select({ line }));
      }
    });

    afterNextRender(() => {
      this.map = L.map(this.mapEl().nativeElement, { preferCanvas: true }).setView(BIALYSTOK, 12);
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
        attribution: '&copy; OpenStreetMap contributors',
      }).addTo(this.map);
      this.layers = L.layerGroup().addTo(this.map);
      this.mapReady.set(true);
    });
    inject(DestroyRef).onDestroy(() => this.map?.remove());

    effect(() => {
      const detail = this.detail();
      if (this.mapReady() && detail) this.drawRoute(detail);
    });
  }

  protected select(line: string): void {
    this.router.navigate([], { queryParams: { line }, replaceUrl: true });
  }

  /** CSS custom property name of a subtype's colour, e.g. "--line-suburban". */
  protected colorVar(subtype: RouteSubtype): string {
    return SUBTYPES.find((s) => s.subtype === subtype)?.color ?? '--line-urban';
  }

  /**
   * Base routes thick, other variants thin and dashed in the same colour, depot runs dotted in yellow; stops as small
   * circles, zone 2 filled.
   */
  private drawRoute(detail: LineDetail): void {
    const layers = this.layers!;
    layers.clearLayers();
    const css = getComputedStyle(document.documentElement);
    const color = css.getPropertyValue(this.colorVar(detail.subtype)).trim() || '#2a78d6';
    const depotColor = css.getPropertyValue('--depot').trim() || '#eda100';
    const ordered = [...detail.variants].sort(
      (a, b) => (a.kind === 'main' ? 1 : 0) - (b.kind === 'main' ? 1 : 0),
    );
    const bounds = L.latLngBounds([]);
    for (const v of ordered) {
      if (!v.points.length) continue;
      const main = v.kind === 'main';
      const depot = v.kind === 'depot';
      L.polyline(v.points, {
        color: depot ? depotColor : color,
        weight: main ? 5 : depot ? 3 : 2,
        opacity: main ? 0.85 : 1,
        dashArray: main ? undefined : depot ? '2 6' : '6 5',
      })
        .bindTooltip(variantLabel(v), { sticky: true })
        .addTo(layers);
      v.points.forEach((p) => bounds.extend(p));
    }
    const seen = new Set<string>();
    for (const v of ordered) {
      for (const s of v.stops) {
        if (s.lat === null || s.lon === null) continue;
        const key = `${s.lat},${s.lon}`;
        if (seen.has(key)) continue;
        seen.add(key);
        const zone2 = s.zone === '2';
        L.circleMarker([s.lat, s.lon], {
          radius: 4,
          color,
          weight: 2,
          fillColor: zone2 ? color : '#ffffff',
          fillOpacity: 1,
        })
          .bindTooltip(`${s.name}${zone2 ? ' · zone 2' : ''}`)
          .addTo(layers);
        bounds.extend([s.lat, s.lon]);
      }
    }
    if (bounds.isValid()) this.map!.fitBounds(bounds, { padding: [24, 24] });
  }
}

function variantLabel(v: LineVariant): string {
  const kind =
    v.kind === 'main' || v.kind === 'depot'
      ? variantKindLabel(v.kind)
      : `${variantKindLabel(v.kind)} variant`;
  return `${kind} towards ${v.headsign}: ${v.km.toFixed(1)} km, ${v.tripsPerDay} trips a weekday`;
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
      label: 'Average',
      color: 'var(--chart-line)',
      points: stats.map((s) => ({ x: s.hour + 0.5, y: s.avg })),
    },
  ];
  if (withMedian) {
    series.push({
      label: 'Median',
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
