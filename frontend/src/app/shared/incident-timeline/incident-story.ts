import { Component, computed, input } from '@angular/core';
import { IncidentSpan } from '../../core/models/models';
import { PathLine, PathMap } from '../map/path-map';
import { cssColor } from '../palette';
import { IncidentTimeline } from './incident-timeline';
import { t } from '../../core/i18n/t';

/**
 * A bus change: a time line of what each bus did, then (when given) where the buses went on a map:
 * the replaced bus in orange, ringed where it was last seen, the one that took over in blue.
 */
@Component({
  selector: 'app-incident-story',
  imports: [IncidentTimeline, PathMap],
  template: `
    <app-incident-timeline [spans]="timeline()" />
    @if (lines().length) {
      <app-path-map [lines]="lines()" [label]="mapLabel()" />
    }
  `,
  styles: `
    :host {
      display: grid;
      gap: 0.75rem;
      max-width: 60rem;
    }
  `,
})
export class IncidentStory {
  readonly timeline = input.required<IncidentSpan[]>();
  /** Where each bus went over the time line; the first is the replaced one. */
  readonly paths = input<{ vehicle: string; points: [number, number][] }[]>([]);

  protected readonly lines = computed<PathLine[]>(() => {
    const colors = [cssColor('--chart-series-2', '#eb6834'), cssColor('--chart-line', '#1e88e5')];
    return this.paths()
      .filter((p) => p.points.length > 0)
      .map((p, i) => ({
        points: p.points,
        color: colors[Math.min(i, colors.length - 1)],
        label: t(i === 0 ? 'incident.replaced' : 'incident.tookOver', { bus: p.vehicle }),
        weight: i === 0 ? 5 : 3,
        end: i === 0 ? t('incident.lastSeen', { bus: p.vehicle }) : undefined,
      }));
  });
  protected readonly mapLabel = computed(() =>
    t('incident.mapOf', {
      buses: this.lines()
        .map((l) => l.label.toLowerCase())
        .join(t('incident.and')),
    }),
  );
}
