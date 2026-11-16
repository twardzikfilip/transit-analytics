import { DecimalPipe } from '@angular/common';
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
import { LineDetail, LineVariant, RouteSubtype } from '../../core/models/models';
import { RouteDetailActions } from '../../store/route-detail/route-detail.actions';
import { routeDetailFeature } from '../../store/route-detail/route-detail.reducer';
import { BrigadeTimeline } from './brigade-timeline';
import { EndStops } from './end-stops';
import { LineVehiclesSection } from './line-vehicles';
import { clock, duration } from '../../shared/format/time';
import { cssColor } from '../../shared/palette';
import { SUBTYPES, groupLines, subtypeLabel, variantKindLabel } from './format';
import { SegmentChart } from './segment-chart';
import { TravelCharts } from './travel-charts';
import { VariantTiles } from './variant-tiles';
import { DepotRuns } from './depot-runs';
import { createBaseMap } from '../../shared/map/base-map';

/**
 * Routes in detail: pick a line to see it on the map with its variants, its timetable figures (lengths, travel
 * times, speeds, zone 2, brigades) and how it actually runs, measured from recorded vehicle positions.
 */
@Component({
  selector: 'app-route-detail',
  imports: [
    DecimalPipe,
    VariantTiles,
    DepotRuns,
    TravelCharts,
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

  protected readonly mains = computed(
    () => this.detail()?.variants.filter((v) => v.kind === 'main') ?? [],
  );
  /** "Piastowska ⇄ Choroszcz/Cmentarz": the two termini of the base route. */
  protected readonly termini = computed(() => {
    const m = this.mains()[0];
    return m ? `${m.firstStop} ⇄ ${m.lastStop}` : '';
  });

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
      this.map = createBaseMap(this.mapEl().nativeElement, { canvas: true });
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
