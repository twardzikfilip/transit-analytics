import { Component, computed, input } from '@angular/core';
import { IncidentSpan, IncidentStep } from '../../core/models/models';
import { localTime } from '../format/time';
import { PathLine, PathMap } from '../map/path-map';
import { cssColor } from '../palette';
import { IncidentTimeline } from './incident-timeline';
import { t } from '../../core/i18n/t';
import { phrasesText } from '../i18n/phrases';

/**
 * A breakdown (a change of bus) told step by step: a time line of what each bus did, with the steps numbered on its
 * axis, then the numbered steps in words, then (when given) where the buses went on a map: the bus that broke down
 * in orange, ringed where it was last seen, the one that took over in blue.
 */
@Component({
  selector: 'app-incident-story',
  imports: [IncidentTimeline, PathMap],
  template: `
    <app-incident-timeline [spans]="timeline()" [story]="story()" />
    <ol class="steps">
      @for (s of story(); track $index) {
        <li>
          <span class="time">{{ time(s.at) }}</span>
          {{ phrasesText(s.text) }}
        </li>
      }
    </ol>
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

    ol.steps {
      margin: 0;
      padding-left: 1.6rem;
      display: grid;
      gap: 0.3rem;

      li {
        padding-left: 0.2rem;
      }

      .time {
        display: inline-block;
        min-width: 3rem;
        font-weight: 600;
        font-variant-numeric: tabular-nums;
      }
    }
  `,
})
export class IncidentStory {
  readonly story = input.required<IncidentStep[]>();
  readonly timeline = input.required<IncidentSpan[]>();
  /** Where each bus went over the time line; the first is the one that broke down. */
  readonly paths = input<{ vehicle: string; points: [number, number][] }[]>([]);
  protected readonly time = localTime;
  protected readonly phrasesText = phrasesText;

  protected readonly lines = computed<PathLine[]>(() => {
    const colors = [cssColor('--chart-series-2', '#eb6834'), cssColor('--chart-line', '#1e88e5')];
    return this.paths()
      .filter((p) => p.points.length > 0)
      .map((p, i) => ({
        points: p.points,
        color: colors[Math.min(i, colors.length - 1)],
        label: t(i === 0 ? 'incident.brokeDown' : 'incident.tookOver', { bus: p.vehicle }),
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
