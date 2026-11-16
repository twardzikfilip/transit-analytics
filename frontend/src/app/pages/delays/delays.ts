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
  untracked,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { Store } from '@ngrx/store';
import * as L from 'leaflet';
import { DelayReplayActions } from '../../store/delay-replay/delay-replay.actions';
import { delayReplayFeature } from '../../store/delay-replay/delay-replay.reducer';
import { formatDelay } from '../positions/delay-bands';
import { toLocalInput } from '../positions/time-window';
import {
  DELAY_STOPS,
  NO_DELAY_COLOR,
  Replay,
  ReplayPosition,
  SPEEDS,
  WINDOW_PRESETS,
  averageDelay,
  defaultSpeed,
  delayColor,
  parseReplay,
  positionsAt,
  resolveDelayWindow,
} from './replay';

const BIALYSTOK: L.LatLngExpression = [53.13, 23.16];

/**
 * Delays: an animated replay of recorded vehicle positions for a time window and lines, each vehicle coloured by how
 * late it is.
 */
@Component({
  selector: 'app-delays',
  imports: [DatePipe],
  templateUrl: './delays.html',
  styleUrl: './delays.scss',
})
export class Delays {
  private readonly store = inject(Store);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly params = toSignal(inject(ActivatedRoute).queryParamMap, { requireSync: true });
  private readonly mapEl = viewChild.required<ElementRef<HTMLDivElement>>('map');

  protected readonly presets = WINDOW_PRESETS;
  protected readonly speeds = SPEEDS;
  protected readonly legend = DELAY_STOPS;
  protected readonly noDelayColor = NO_DELAY_COLOR;

  protected readonly routes = this.store.selectSignal(delayReplayFeature.selectRoutes);
  protected readonly loading = this.store.selectSignal(delayReplayFeature.selectLoading);
  protected readonly error = this.store.selectSignal(delayReplayFeature.selectError);
  private readonly raw = this.store.selectSignal(delayReplayFeature.selectReplay);
  protected readonly replay = computed<Replay | null>(() => {
    const raw = this.raw();
    return raw ? parseReplay(raw) : null;
  });

  /** URL state: ?range=1h or ?from=…&to=…, and ?lines=103,22 (none = all lines). */
  protected readonly window = computed(() =>
    resolveDelayWindow(
      {
        range: this.params().get('range'),
        from: this.params().get('from'),
        to: this.params().get('to'),
      },
      new Date(),
    ),
  );
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

  private map?: L.Map;
  private readonly mapReady = signal(false);
  private readonly markers = new Map<string, L.CircleMarker>();
  /** Latest shown state per vehicle, for tooltips. */
  private readonly shown = new Map<string, ReplayPosition>();

  constructor() {
    afterNextRender(() => {
      this.map = L.map(this.mapEl().nativeElement, { preferCanvas: true }).setView(BIALYSTOK, 12);
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
        attribution: '&copy; OpenStreetMap contributors',
      }).addTo(this.map);
      this.mapReady.set(true);
    });
    this.destroyRef.onDestroy(() => {
      this.stop();
      this.map?.remove();
    });

    // Load whenever the window or lines in the URL change.
    inject(ActivatedRoute)
      .queryParamMap.pipe(takeUntilDestroyed())
      .subscribe((params) => {
        const w = resolveDelayWindow(
          { range: params.get('range'), from: params.get('from'), to: params.get('to') },
          new Date(),
        );
        const routes = (params.get('lines') ?? '')
          .split(',')
          .map((l) => l.trim())
          .filter(Boolean);
        this.store.dispatch(
          DelayReplayActions.load({
            query: { from: w.from.toISOString(), to: w.to.toISOString(), routes },
          }),
        );
      });

    // A new replay starts from the beginning, at a speed that suits its length, and plays.
    effect(() => {
      const replay = this.replay();
      if (!replay || !this.mapReady()) return;
      untracked(() => {
        this.speed.set(defaultSpeed(replay.to - replay.from));
        this.seek(replay.frames[0]?.time ?? replay.from);
        if (replay.frames.length) this.play();
      });
    });
  }

  // --- filters -------------------------------------------------------------------------------------------------

  protected setPreset(key: string): void {
    this.navigate({ range: key, from: null, to: null });
  }

  protected setCustom(which: 'from' | 'to', value: string): void {
    if (!value) return;
    const w = this.window();
    this.navigate({
      range: null,
      from: which === 'from' ? value : toLocalInput(w.from),
      to: which === 'to' ? value : toLocalInput(w.to),
    });
  }

  protected toggleLine(line: string, on: boolean): void {
    const lines = new Set(this.selectedLines());
    if (on) lines.add(line);
    else lines.delete(line);
    this.navigate({ lines: lines.size ? [...lines].join(',') : null });
  }

  protected allLines(): void {
    this.navigate({ lines: null });
  }

  protected localInput(date: Date): string {
    return toLocalInput(date);
  }

  private navigate(queryParams: Record<string, string | null>): void {
    void this.router.navigate([], { queryParams, queryParamsHandling: 'merge' });
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
    }
  }

  private render(positions: ReplayPosition[]): void {
    if (!this.map) return;
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
          color: '#1f2933',
          weight: 1,
          fillColor: color,
          fillOpacity: 0.95,
        })
          .bindTooltip(() => this.tooltip(p.vehicle), { direction: 'top', offset: [0, -6] })
          .addTo(this.map);
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
    const delay =
      p.delaySeconds === null
        ? 'delay unknown'
        : p.delaySeconds < 60
          ? 'on time'
          : `${formatDelay(p.delaySeconds)} min late`;
    return `<strong>Line ${escapeHtml(p.line ?? '?')}</strong> · bus ${escapeHtml(vehicle)}<br>${delay}`;
  }
}

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
}
