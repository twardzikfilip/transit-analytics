import { Component, DestroyRef, ElementRef, afterNextRender, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import * as L from 'leaflet';
import { catchError, of, switchMap, timer } from 'rxjs';
import { VehiclePositionApi } from '../../core/api/api.services';
import { VehiclePosition } from '../../core/models/models';

const REFRESH_MS = 5000;

@Component({
  selector: 'app-live-map',
  templateUrl: './live-map.html',
  styleUrl: './live-map.scss',
})
export class LiveMap {
  private readonly api = inject(VehiclePositionApi);
  private readonly destroyRef = inject(DestroyRef);
  private readonly mapEl = viewChild.required<ElementRef<HTMLDivElement>>('map');

  private map?: L.Map;
  private readonly markers = new Map<number, L.CircleMarker>();

  protected readonly count = signal(0);
  protected readonly lastUpdate = signal<Date | null>(null);
  protected readonly error = signal(false);

  constructor() {
    afterNextRender(() => {
      this.map = L.map(this.mapEl().nativeElement).setView([52.52, 13.405], 11);
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
        attribution: '&copy; OpenStreetMap contributors',
      }).addTo(this.map);
      this.startPolling();
    });
    this.destroyRef.onDestroy(() => this.map?.remove());
  }

  private startPolling(): void {
    timer(0, REFRESH_MS)
      .pipe(
        switchMap(() =>
          this.api.latest().pipe(
            catchError(() => {
              this.error.set(true);
              return of(null);
            }),
          ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((positions) => {
        if (positions) {
          this.error.set(false);
          this.render(positions);
        }
      });
  }

  private render(positions: VehiclePosition[]): void {
    if (!this.map) return;
    const seen = new Set<number>();
    for (const p of positions) {
      seen.add(p.vehicleId);
      const latLng: L.LatLngExpression = [p.latitude, p.longitude];
      const popup = `<strong>${escapeHtml(p.vehicleNumber)}</strong><br>${new Date(p.recordedAt).toLocaleTimeString()}`;
      const existing = this.markers.get(p.vehicleId);
      if (existing) {
        existing.setLatLng(latLng).setPopupContent(popup);
      } else {
        const marker = L.circleMarker(latLng, { radius: 8, color: '#1e88e5', fillOpacity: 0.85 })
          .bindPopup(popup)
          .addTo(this.map);
        this.markers.set(p.vehicleId, marker);
      }
    }
    for (const [id, marker] of this.markers) {
      if (!seen.has(id)) {
        marker.remove();
        this.markers.delete(id);
      }
    }
    this.count.set(positions.length);
    this.lastUpdate.set(new Date());
  }
}

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
}
