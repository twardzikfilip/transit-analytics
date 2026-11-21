import { DatePipe } from '@angular/common';
import {
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { Store } from '@ngrx/store';
import { injectSetQuery } from '../../shared/url-query';
import * as L from 'leaflet';
import { DelayReplayActions } from '../../store/delay-replay/delay-replay.actions';
import { delayReplayFeature } from '../../store/delay-replay/delay-replay.reducer';
import { formatDelay } from '../../shared/format/time';
import { TimeWindowPicker } from '../../shared/time-window/time-window-picker';
import { readWindow, windowQuery } from '../../shared/time-window/time-window';
import {
  DELAY_STOPS,
  NO_DELAY_COLOR,
  Replay,
  ReplayPosition,
  SPEEDS,
  averageDelay,
  defaultSpeed,
  delayColor,
  parseReplay,
  positionsAt,
} from './replay';
import { escapeHtml } from '../../shared/format/html';
import { leafletHost } from '../../shared/map/leaflet-host';
import { injectBusLanes } from '../../shared/map/bus-lanes';
import { injectCityBoundaries } from '../../shared/map/city-boundaries';
import { BOUNDARY_STYLES, BUS_LANE_STYLE, INK } from '../../shared/palette';
import {
  BrigadeSchedules,
  brigadeSection,
  serviceDayOf,
} from '../../shared/brigade/brigade-schedules';

export const DEFAULT_PRESET = '1h';

/** The replay moment in a popup, e.g. "Mon 16 Nov, 11:05". */
const POPUP_TIME = new Intl.DateTimeFormat('en-GB', {
  weekday: 'short',
  day: 'numeric',
  month: 'short',
  hour: '2-digit',
  minute: '2-digit',
});

/**
 * Delays: an animated replay of recorded vehicle positions for a time window and lines, each vehicle coloured by how
 * late it is.
 */
@Component({
  selector: 'app-delays',
  imports: [DatePipe, TimeWindowPicker],
  templateUrl: './delays.html',
  styleUrl: './delays.scss',
})
export class Delays {
  private readonly store = inject(Store);
  private readonly navigate = injectSetQuery();
  private readonly destroyRef = inject(DestroyRef);
  private readonly params = toSignal(inject(ActivatedRoute).queryParamMap, { requireSync: true });
  private readonly mapEl = viewChild.required<ElementRef<HTMLDivElement>>('map');

  protected readonly defaultPreset = DEFAULT_PRESET;
  protected readonly speeds = SPEEDS;
  protected readonly legend = DELAY_STOPS;
  protected readonly noDelayColor = NO_DELAY_COLOR;
  protected readonly boundaries = [
    BOUNDARY_STYLES.city,
    BOUNDARY_STYLES.centre,
    { color: BUS_LANE_STYLE.color, dashArray: undefined, label: BUS_LANE_STYLE.label },
  ];

  protected readonly routes = this.store.selectSignal(delayReplayFeature.selectRoutes);
  protected readonly loading = this.store.selectSignal(delayReplayFeature.selectLoading);
  protected readonly error = this.store.selectSignal(delayReplayFeature.selectError);
  private readonly raw = this.store.selectSignal(delayReplayFeature.selectReplay);
  protected readonly replay = computed<Replay | null>(() => {
    const raw = this.raw();
    return raw ? parseReplay(raw) : null;
  });

  /** URL state: the time window (see app-time-window) and ?lines=103,22 (none = all lines). */
  protected readonly selectedLines = computed(() =>
    (this.params().get('lines') ?? '')
      .split(',')
      .map((l) => l.trim())
      .filter(Boolean),
  );
  protected readonly linesLabel = computed(() => {
    const lines = this.selectedLines();
    if (!lines.length) return 'All lines';
    return lines.length <= 4 ? `Lines ${lines.join(', ')}` : `${lines.length} lines`;
  });

  /** Playback. The replay clock runs outside signals; `now` mirrors it ~10 times a second for the controls. */
  protected readonly playing = signal(false);
  protected readonly speed = signal(60);
  protected readonly loop = signal(true);
  protected readonly now = signal(0);
  protected readonly visibleCount = signal(0);
  /** Mean delay of the vehicles on the map at the current moment (updated with the animation). */
  protected readonly average = signal<{ seconds: number; vehicles: number } | null>(null);
  protected readonly delayColor = delayColor;
  protected readonly formatDelay = formatDelay;
  private clock = 0;
  private lastFrame: number | null = null;
  private lastUiUpdate = 0;
  private animation: number | null = null;

  private readonly host = leafletHost(() => this.mapEl().nativeElement, {
    canvas: true,
    setUp: (map) => {
      this.drawBoundaries(map);
      this.drawBusLanes(map);
    },
  });
  private readonly markers = new Map<string, L.CircleMarker>();
  /** Latest shown state per vehicle, for tooltips. */
  private readonly shown = new Map<string, ReplayPosition>();
  /** The vehicle whose popup is open: it follows the replay clock. */
  private popupVehicle: string | null = null;
  /** Brigade schedules for popups, for the service day being replayed. */
  private readonly schedules = new BrigadeSchedules(() => this.refreshPopup());
  private readonly drawBoundaries = injectCityBoundaries();
  private readonly drawBusLanes = injectBusLanes();

  constructor() {
    this.destroyRef.onDestroy(() => this.stop());

    // Load whenever the window or lines in the URL change.
    inject(ActivatedRoute)
      .queryParamMap.pipe(takeUntilDestroyed())
      .subscribe((params) => {
        const w = readWindow(params, DEFAULT_PRESET);
        const routes = (params.get('lines') ?? '')
          .split(',')
          .map((l) => l.trim())
          .filter(Boolean);
        this.store.dispatch(
          DelayReplayActions.load({
            query: { ...windowQuery(w), routes },
          }),
        );
      });

    // A new replay starts from the beginning, at a speed that suits its length, and plays.
    effect(() => {
      const replay = this.replay();
      if (!replay || !this.host.ready()) return;
      untracked(() => {
        this.speed.set(defaultSpeed(replay.to - replay.from));
        this.seek(replay.frames[0]?.time ?? replay.from);
        if (replay.frames.length) this.play();
      });
    });
  }

  // --- filters -------------------------------------------------------------------------------------------------

  protected toggleLine(line: string, on: boolean): void {
    const lines = new Set(this.selectedLines());
    if (on) lines.add(line);
    else lines.delete(line);
    this.navigate({ lines: lines.size ? [...lines].join(',') : null });
  }

  protected allLines(): void {
    this.navigate({ lines: null });
  }

  // --- playback ------------------------------------------------------------------------------------------------

  protected togglePlay(): void {
    if (this.playing()) {
      this.stop();
      return;
    }
    const replay = this.replay();
    // Playing again at the end starts over.
    if (replay && this.clock >= this.end(replay)) this.seek(this.start(replay));
    this.play();
  }

  protected onScrub(value: string): void {
    this.seek(Number(value));
  }

  private play(): void {
    if (this.animation !== null) return;
    this.playing.set(true);
    this.lastFrame = null;
    this.animation = requestAnimationFrame((t) => this.tick(t));
  }

  private stop(): void {
    if (this.animation !== null) cancelAnimationFrame(this.animation);
    this.animation = null;
    this.playing.set(false);
  }

  private tick(timestamp: number): void {
    const replay = this.replay();
    if (!replay) return this.stop();
    if (this.lastFrame !== null) {
      this.clock += (timestamp - this.lastFrame) * this.speed();
      if (this.clock >= this.end(replay)) {
        if (this.loop()) {
          this.clock = this.start(replay);
        } else {
          this.clock = this.end(replay);
          this.draw(replay, true);
          return this.stop();
        }
      }
    }
    this.lastFrame = timestamp;
    this.draw(replay, timestamp - this.lastUiUpdate > 100);
    if (timestamp - this.lastUiUpdate > 100) this.lastUiUpdate = timestamp;
    this.animation = requestAnimationFrame((t) => this.tick(t));
  }

  private seek(time: number): void {
    this.clock = time;
    const replay = this.replay();
    if (replay) this.draw(replay, true);
  }

  /** The replay runs from the first to the last frame (empty stretches before or after are skipped). */
  private start(replay: Replay): number {
    return replay.frames[0]?.time ?? replay.from;
  }

  private end(replay: Replay): number {
    const last = replay.frames[replay.frames.length - 1];
    return last ? last.time + replay.stepMs : replay.to;
  }

  protected readonly range = computed(() => {
    const replay = this.replay();
    return replay ? { min: this.start(replay), max: this.end(replay), step: 1000 } : null;
  });

  private draw(replay: Replay, updateUi: boolean): void {
    const positions = positionsAt(replay, this.clock);
    this.render(positions);
    if (updateUi) {
      this.now.set(this.clock);
      this.visibleCount.set(positions.length);
      this.average.set(averageDelay(positions));
      this.refreshPopup();
    }
  }

  private render(positions: ReplayPosition[]): void {
    if (!this.host.map) return;
    const seen = new Set<string>();
    for (const p of positions) {
      seen.add(p.vehicle);
      this.shown.set(p.vehicle, p);
      const color = delayColor(p.delaySeconds);
      const existing = this.markers.get(p.vehicle);
      if (existing) {
        existing.setLatLng([p.latitude, p.longitude]);
        if (existing.options.fillColor !== color) existing.setStyle({ fillColor: color });
      } else {
        const marker = L.circleMarker([p.latitude, p.longitude], {
          radius: 6,
          color: INK,
          weight: 1,
          fillColor: color,
          fillOpacity: 0.95,
        })
          .bindTooltip(() => this.tooltip(p.vehicle), { direction: 'top', offset: [0, -6] })
          .bindPopup('', { maxWidth: 320 })
          .on('popupopen', () => {
            this.popupVehicle = p.vehicle;
            this.refreshPopup();
          })
          .on('popupclose', () => {
            if (this.popupVehicle === p.vehicle) this.popupVehicle = null;
          })
          .addTo(this.host.map);
        this.markers.set(p.vehicle, marker);
      }
    }
    for (const [vehicle, marker] of this.markers) {
      if (!seen.has(vehicle)) {
        marker.remove();
        this.markers.delete(vehicle);
        this.shown.delete(vehicle);
      }
    }
  }

  private tooltip(vehicle: string): string {
    const p = this.shown.get(vehicle);
    if (!p) return '';
    const brigade = p.brigade ? ` · brigade ${escapeHtml(p.brigade)}` : '';
    return `<strong>Line ${escapeHtml(p.line ?? '?')}</strong> · bus ${escapeHtml(vehicle)}${brigade}<br>${delayText(p)}`;
  }

  /**
   * The open popup at the replay's moment: the bus, and its brigade with the rest of that day's plan. Fetches the
   * brigade's schedule for the day being replayed when it isn't at hand.
   */
  private refreshPopup(): void {
    const vehicle = this.popupVehicle;
    const p = vehicle ? this.shown.get(vehicle) : undefined;
    const marker = vehicle ? this.markers.get(vehicle) : undefined;
    if (!p || !marker) return;
    const at = new Date(this.clock);
    const schedule = p.brigade ? this.schedules.get(p.brigade, at) : undefined;
    const today = serviceDayOf(at) === serviceDayOf(new Date());
    const html =
      `<strong>Line ${escapeHtml(p.line ?? '?')}</strong> · bus ${escapeHtml(vehicle!)}<br>${delayText(p)}` +
      `<br><span class="muted">as of ${POPUP_TIME.format(at)}</span>` +
      brigadeSection(p.brigade, schedule, at, today);
    // Redrawn about ten times a second while playing: only touch the DOM when something changed.
    if (marker.getPopup()?.getContent() !== html) marker.setPopupContent(html);
  }
}

function delayText(p: ReplayPosition): string {
  if (p.delaySeconds === null) return 'delay unknown';
  return p.delaySeconds < 60 ? 'on time' : `${formatDelay(p.delaySeconds)} min late`;
}
