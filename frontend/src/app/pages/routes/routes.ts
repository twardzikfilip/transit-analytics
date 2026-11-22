import {
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Store } from '@ngrx/store';
import * as L from 'leaflet';
import { LineDetail, LineVariant, RouteSubtype } from '../../core/models/models';
import { escapeHtml } from '../../shared/format/html';
import { LineStatsActions } from '../../store/line-stats/line-stats.actions';
import { lineStatsFeature } from '../../store/line-stats/line-stats.reducer';
import { RouteDetailActions } from '../../store/route-detail/route-detail.actions';
import { routeDetailFeature } from '../../store/route-detail/route-detail.reducer';
import { BrigadeTimeline } from './brigade-timeline';
import { EndStops } from './end-stops';
import { LineVehiclesSection } from './line-vehicles';
import { LineRegularity } from './variant-tiles';
import { clock, duration } from '../../shared/format/time';
import { cssColor, INK, WHITE } from '../../shared/palette';
import {
  GEOMETRIES,
  SUBTYPES,
  capacityDescription,
  capacityLabel,
  geometryDescription,
  geometryLabel,
  subtypeLabel,
  variantKindLabel,
} from './format';
import { TravelCharts } from './travel-charts';
import { VariantTiles } from './variant-tiles';
import { VariantTable } from './variant-table';
import { SpeedProfiles } from './speed-profiles';
import { DepotRuns } from './depot-runs';
import { leafletHost } from '../../shared/map/leaflet-host';
import { injectBusLanes } from '../../shared/map/bus-lanes';
import { injectCityBoundaries } from '../../shared/map/city-boundaries';
import { BOUNDARY_STYLES, BUS_LANE_STYLE, VARIANT_COLORS } from '../../shared/palette';
import { Skeleton } from '../../shared/skeleton/skeleton';
import { LocalDatePipe, LocalNumberPipe } from '../../shared/i18n/locale-pipes';
import { t, tn } from '../../core/i18n/t';

/** A recorded variant driven less than this share of the runs is drawn thin and dashed. */
export const RARE_SHARE = 0.05;

/**
 * One route (/routes/route/103): the line (city, suburban, night or regional) on the map with its variants, its
 * timetable figures (lengths, travel times, speeds, zone 2, brigades) and how it actually runs, measured from
 * recorded vehicle positions; with links to its trips and its timetable on the operator's website. Regional lines of
 * other operators aren't in the timetable: only what was recorded is shown for them.
 */

@Component({
  selector: 'app-routes',
  imports: [
    Skeleton,
    RouterLink,
    LocalDatePipe,
    LocalNumberPipe,
    VariantTiles,
    VariantTable,
    SpeedProfiles,
    DepotRuns,
    TravelCharts,
    BrigadeTimeline,
    EndStops,
    LineVehiclesSection,
  ],
  templateUrl: './routes.html',
  styleUrl: './routes.scss',
})
export class RoutesPage {
  protected readonly t = t;
  protected readonly tn = tn;
  private readonly store = inject(Store);
  private readonly router = inject(Router);
  private readonly path = toSignal(inject(ActivatedRoute).paramMap, { requireSync: true });
  private readonly mapEl = viewChild.required<ElementRef<HTMLDivElement>>('map');

  protected readonly detail = this.store.selectSignal(routeDetailFeature.selectDetail);
  protected readonly performance = this.store.selectSignal(routeDetailFeature.selectPerformance);
  protected readonly loadingDetail = this.store.selectSignal(
    routeDetailFeature.selectLoadingDetail,
  );
  protected readonly loadingPerformance = this.store.selectSignal(
    routeDetailFeature.selectLoadingPerformance,
  );
  protected readonly error = this.store.selectSignal(routeDetailFeature.selectError);

  /** Every line's weekday statistics (GET /api/lines/stats), for the chosen line's regularity tile. */
  private readonly lineStats = this.store.selectSignal(lineStatsFeature.selectReport);
  /** The chosen line's regularity in peak and off-peak hours, and when those are; null until known. */
  protected readonly regularity = computed((): LineRegularity | null => {
    const report = this.lineStats();
    const id = this.detail()?.id;
    const stats = report && id != null ? report.lines.find((l) => l.routeId === id) : undefined;
    return stats && report
      ? {
          peakCv: stats.peakRegularityCv,
          offPeakCv: stats.offPeakRegularityCv,
          peak: report.peak,
          offPeak: report.offPeak,
        }
      : null;
  });

  protected readonly geometries = GEOMETRIES;
  /** What a line's classes mean, for the line title. */
  protected readonly classes = computed(() => {
    const d = this.detail();
    if (!d) return null;
    return {
      geometry: d.geometry
        ? { label: geometryLabel(d.geometry), description: geometryDescription(d.geometry) }
        : null,
      capacity: d.capacityClass
        ? {
            label: t('routes.capacityOf', { capacity: capacityLabel(d.capacityClass) }),
            description: capacityDescription(d.capacityClass),
          }
        : null,
    };
  });
  /** The line in the path (/routes/route/103). */
  protected readonly selected = computed(() => this.path().get('line'));

  protected readonly clock = clock;
  protected readonly duration = duration;
  protected readonly subtypeLabel = subtypeLabel;

  protected readonly mains = computed(
    () => this.detail()?.variants.filter((v) => v.kind === 'main') ?? [],
  );
  /** "Piastowska ⇄ Choroszcz/Cmentarz": the two termini of the base route. */
  protected readonly termini = computed(() => {
    const m = this.mains()[0];
    return m ? `${m.firstStop} ⇄ ${m.lastStop}` : '';
  });

  /** A line without a timetable: its recorded variants, each with its colour and whether it was driven rarely. */
  protected readonly recorded = computed(() => {
    const r = this.detail()?.recorded;
    if (!r?.variants.length) return null;
    return {
      ...r,
      variants: r.variants.map((v, i) => ({
        ...v,
        color: VARIANT_COLORS[i % VARIANT_COLORS.length],
        rare: v.runs < RARE_SHARE * r.runs,
      })),
    };
  });
  /** The recorded variant chosen in the list: the map highlights it. */
  protected readonly chosenVariant = signal<number | null>(null);
  /** Whether the map has something to show: a timetable route, or recorded variants. */
  protected readonly hasMap = computed(() => {
    const d = this.detail();
    return !d || d.id !== null || this.recorded() !== null;
  });

  private readonly host = leafletHost(() => this.mapEl().nativeElement, {
    canvas: true,
    setUp: (map) => {
      this.drawBoundaries(map);
      this.drawBusLanes(map);
    },
  });
  /** The line on the map, so a recorded variant chosen stays chosen while the same line is shown. */
  private drawnLine: string | null = null;
  /** City limits, and the centre lines are classified by (geometry). */
  private readonly drawBoundaries = injectCityBoundaries('lineCentre');
  private readonly drawBusLanes = injectBusLanes();
  protected readonly busLaneStyle = BUS_LANE_STYLE;
  protected readonly boundaryStyles = BOUNDARY_STYLES;

  constructor() {
    this.store.dispatch(LineStatsActions.load());

    // Load the selected line once per change.
    let loaded: string | null = null;
    effect(() => {
      const line = this.selected();
      if (line && line !== loaded) {
        loaded = line;
        this.store.dispatch(RouteDetailActions.select({ line }));
      }
    });

    effect(() => {
      const detail = this.detail();
      if (this.host.ready() && detail) {
        if (detail.id === null) {
          // Drawn again when the line's data changes, not when a variant is chosen (that redraws on its own).
          untracked(() => {
            if (detail.line !== this.drawnLine) this.chosenVariant.set(null);
            this.drawnLine = detail.line;
            this.drawRecorded();
          });
        } else {
          this.drawnLine = detail.line;
          this.drawRoute(detail);
        }
      }
    });
  }

  /** Back to every route. */
  protected back(): void {
    void this.router.navigate(['/routes']);
  }

  /** CSS custom property name of a subtype's colour, e.g. "--line-suburban". */
  protected colorVar(subtype: RouteSubtype): string {
    return SUBTYPES.find((s) => s.subtype === subtype)?.color ?? '--line-urban';
  }

  /**
   * Base routes thick, other variants thin and dashed in the same colour, depot runs dotted in yellow; stops as small
   * circles, zone 2 filled; every depot's area, and dashed yellow lines along the streets between the operator's
   * depot and where the line's brigades start and end their day.
   */
  private drawRoute(detail: LineDetail): void {
    const layers = this.host.layer;
    layers.clearLayers();
    const color = cssColor(this.colorVar(detail.subtype), '#2a78d6');
    const depotColor = cssColor('--depot', '#eda100');
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
          fillColor: zone2 ? color : WHITE,
          fillOpacity: 1,
        })
          .bindTooltip(`${s.name}${zone2 ? ' · zone 2' : ''}`)
          .addTo(layers);
        bounds.extend([s.lat, s.lon]);
      }
    }
    // Every depot's area, the one this line's operator runs from named.
    const own = detail.operator?.code;
    for (const d of detail.depots) {
      for (const ring of d.outlines) {
        const area = L.polygon(ring, {
          color: INK,
          weight: d.code === own ? 2.5 : 1.5,
          fillColor: depotColor,
          fillOpacity: d.code === own ? 0.6 : 0.35,
        })
          .bindTooltip(`${d.name}${d.address ? ', ' + d.address : ''}`, {
            permanent: d.code === own && ring === d.outlines[0],
            direction: 'right',
            className: 'depot-label',
          })
          .addTo(layers);
        if (d.code === own) ring.forEach((p) => bounds.extend(p));
        if (d.code !== own) area.bringToBack();
      }
    }
    // The way between the depot and where brigades start or end their day: along the streets, dashed.
    for (const l of detail.depotLinks) {
      const out = l.kind === 'pull_out';
      const depot = detail.depots.find((d) => d.code === l.depot)?.name ?? l.depot;
      L.polyline(l.path, { color: depotColor, weight: 3, dashArray: '8 6', opacity: 0.95 })
        .bindTooltip(
          (out
            ? `${brigadesText(l.brigades)} from ${depot} start at ${l.stop}`
            : `${brigadesText(l.brigades)} end at ${l.stop} and go back to ${depot}`) +
            (l.routed ? '' : ' (straight line: no street route found)'),
          { sticky: true },
        )
        .addTo(layers);
      L.circleMarker([l.lat, l.lon], {
        radius: 6,
        color: depotColor,
        weight: 3,
        fillColor: WHITE,
        fillOpacity: 1,
      })
        .bindTooltip(`${l.stop}: ${out ? 'buses enter service' : 'buses leave service'}`)
        .addTo(layers);
      l.path.forEach((p) => bounds.extend(p));
    }
    // The map may just have been shown again (it is hidden for lines without a timetable).
    this.host.map.invalidateSize();
    if (bounds.isValid()) this.host.map.fitBounds(bounds, { padding: [24, 24] });
  }
  /** Chooses a recorded variant in the list (again: none); the map highlights it and moves to it. */
  protected chooseVariant(id: number): void {
    this.chosenVariant.set(this.chosenVariant() === id ? null : id);
    this.drawRecorded(true);
  }

  /**
   * A line without a timetable: where its buses went, from their recorded positions. Each variant in its colour,
   * the most driven on top, rare ones thin and dashed; a hollow circle where runs start and a filled one where they
   * end. A chosen variant is drawn thick, the others faded.
   */
  private drawRecorded(move = false): void {
    const layers = this.host.layer;
    layers.clearLayers();
    const r = this.recorded();
    if (!r) return;
    const chosen = this.chosenVariant();
    const bounds = L.latLngBounds([]);
    const chosenBounds = L.latLngBounds([]);
    // The least driven first, so the main ones lie on top; the chosen one last of all.
    const ordered = [...r.variants]
      .reverse()
      .sort((a, b) => Number(a.id === chosen) - Number(b.id === chosen));
    for (const v of ordered) {
      if (!v.path.length) continue;
      const faded = chosen !== null && v.id !== chosen;
      const tip =
        `<strong>${escapeHtml(v.headsign)}</strong><br>` +
        `${escapeHtml(v.from ?? '?')} → ${escapeHtml(v.to ?? '?')}<br>` +
        `driven ${v.runs} ${v.runs === 1 ? 'time' : 'times'}, ${v.km.toFixed(1)} km`;
      L.polyline(v.path, {
        color: v.color,
        weight: v.id === chosen ? 7 : v.rare ? 2 : 4,
        opacity: faded ? 0.25 : 0.9,
        dashArray: v.rare && v.id !== chosen ? '6 5' : undefined,
      })
        .bindTooltip(tip, { sticky: true })
        .on('click', () => this.chooseVariant(v.id))
        .addTo(layers);
      const end = (at: [number, number], filled: boolean) =>
        L.circleMarker(at, {
          radius: 5,
          color: v.color,
          weight: 2,
          opacity: faded ? 0.25 : 1,
          fillColor: filled ? v.color : WHITE,
          fillOpacity: faded ? 0.25 : 1,
        })
          .bindTooltip(`${escapeHtml(v.headsign)}: ${filled ? 'runs end' : 'runs start'} here`)
          .addTo(layers);
      end(v.path[0], false);
      end(v.path[v.path.length - 1], true);
      v.path.forEach((p) => bounds.extend(p));
      if (v.id === chosen) v.path.forEach((p) => chosenBounds.extend(p));
    }
    this.host.map.invalidateSize();
    const frame = chosenBounds.isValid() ? chosenBounds : bounds;
    if ((move || !chosenBounds.isValid()) && frame.isValid()) {
      this.host.map.fitBounds(frame, { padding: [24, 24] });
    }
  }
}

function brigadesText(n: number): string {
  return n === 1 ? '1 brigade' : `${n} brigades`;
}

function variantLabel(v: LineVariant): string {
  const kind =
    v.kind === 'main' || v.kind === 'depot'
      ? variantKindLabel(v.kind)
      : `${variantKindLabel(v.kind)} variant`;
  return `${kind} towards ${v.headsign}: ${v.km.toFixed(1)} km, ${v.tripsPerDay} trips a weekday`;
}
