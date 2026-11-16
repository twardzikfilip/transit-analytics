import { DatePipe } from '@angular/common';
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
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { Store } from '@ngrx/store';
import * as L from 'leaflet';
import { map, timer } from 'rxjs';
import { LivePosition } from '../../core/models/models';
import { LivePositionsActions } from '../../store/live-positions/live-positions.actions';
import { livePositionsFeature } from '../../store/live-positions/live-positions.reducer';
import { delayedOver, parseMinDelay } from './delay-filter';

const REFRESH_MS = 5000;
const BIALYSTOK: L.LatLngExpression = [53.13, 23.16];
const DEFAULT_COLOR = '#1e88e5';

@Component({
  selector: 'app-live-map',
  imports: [DatePipe],
  templateUrl: './live-map.html',
  styleUrl: './live-map.scss',
})
export class LiveMap {
  private readonly store = inject(Store);
  private readonly destroyRef = inject(DestroyRef);
  private readonly mapEl = viewChild.required<ElementRef<HTMLDivElement>>('map');

  private map?: L.Map;
  private readonly mapReady = signal(false);
  private readonly markers = new Map<string, L.CircleMarker>();

  protected readonly positions = this.store.selectSignal(livePositionsFeature.selectAll);
  protected readonly count = this.store.selectSignal(livePositionsFeature.selectTotal);
  protected readonly lastUpdated = this.store.selectSignal(livePositionsFeature.selectLastUpdated);
  protected readonly error = this.store.selectSignal(livePositionsFeature.selectError);

  private readonly router = inject(Router);
  /** "Delay over N minutes" filter, kept in the URL (?minDelay=5); null = show all vehicles. */
  protected readonly minDelay = toSignal(
    inject(ActivatedRoute).queryParamMap.pipe(map((params) => parseMinDelay(params.get('minDelay')))),
    { initialValue: null },
  );
  /** The vehicles on the map. */
  protected readonly visible = computed(() => delayedOver(this.positions(), this.minDelay()));

  constructor() {
    afterNextRender(() => {
      this.map = L.map(this.mapEl().nativeElement).setView(BIALYSTOK, 12);
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
        attribution: '&copy; OpenStreetMap contributors',
      }).addTo(this.map);
      this.mapReady.set(true);
    });
    this.destroyRef.onDestroy(() => this.map?.remove());

    // Poll through the store: the effect fetches, the reducer holds the latest positions.
    timer(0, REFRESH_MS)
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.store.dispatch(LivePositionsActions.load()));

    effect(() => {
      const positions = this.visible();
      if (this.mapReady()) this.render(positions);
    });
  }

  protected setMinDelay(value: string): void {
    const minutes = parseMinDelay(value);
    // replaceUrl: typing a number shouldn't add a history entry per keystroke.
    void this.router.navigate([], { queryParams: { minDelay: minutes }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  private render(positions: LivePosition[]): void {
    if (!this.map) return;
    const seen = new Set<string>();
    for (const p of positions) {
      seen.add(p.vehicleNumber);
      const latLng: L.LatLngExpression = [p.latitude, p.longitude];
      const color = safeColor(p.routeColor);
      const existing = this.markers.get(p.vehicleNumber);
      if (existing) {
        existing.setLatLng(latLng).setStyle({ color, fillColor: color }).setPopupContent(popup(p));
      } else {
        const marker = L.circleMarker(latLng, { radius: 7, color, fillColor: color, fillOpacity: 0.85, weight: 2 })
          .bindPopup(popup(p))
          .bindTooltip(escapeHtml(p.routeName ?? p.vehicleNumber), { direction: 'top', offset: [0, -6] })
          .addTo(this.map);
        this.markers.set(p.vehicleNumber, marker);
      }
    }
    for (const [id, marker] of this.markers) {
      if (!seen.has(id)) {
        marker.remove();
        this.markers.delete(id);
      }
    }
  }
}

function popup(p: LivePosition): string {
  const line = [p.routeName, p.headsign].filter(Boolean).map((v) => escapeHtml(v!)).join(' → ');
  const vehicle = [p.vehicleNumber, p.model, p.agency].filter(Boolean).map((v) => escapeHtml(v!)).join(' · ');
  const next = p.upcomingStops[0];
  const nextStop = next
    ? `<br>Next: ${escapeHtml(next.name)}${next.scheduledArrival ? ` (${time(next.scheduledArrival)})` : ''}`
    : '';
  const seen = p.timestamp ? `<br><span class="muted">as of ${time(p.timestamp)}</span>` : '';
  return `<strong>${line || escapeHtml(p.vehicleNumber)}</strong><br>${vehicle}<br>${delay(p.delaySeconds)}${nextStop}${seen}`;
}

function delay(seconds: number | null): string {
  if (seconds === null) return 'Delay unknown';
  if (Math.abs(seconds) < 30) return 'On time';
  const abs = Math.abs(seconds);
  const text = `${Math.floor(abs / 60)}:${String(abs % 60).padStart(2, '0')}`;
  return seconds > 0 ? `${text} late` : `${text} early`;
}

function time(iso: string): string {
  return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

/** Route colours come from an external feed; only accept plain hex colours. */
function safeColor(color: string | null): string {
  return color && /^#[0-9a-f]{6}$/i.test(color) ? color : DEFAULT_COLOR;
}

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
}
