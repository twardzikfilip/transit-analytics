import { Component, computed, input } from '@angular/core';
import { RailBusComparison, RailBusJourney } from '../../core/models/models';
import { t } from '../../core/i18n/t';
import { LocalDatePipe } from '../../shared/i18n/locale-pipes';

/** Which way is faster between two stations; null when they take as long, or neither goes. */
type Faster = 'train' | 'bus' | null;

/**
 * Train against bus between every two stations in the city: the fastest journey each way, the routes taken, and how
 * much longer the bus takes. The stations' bus stops are listed below.
 */
@Component({
  selector: 'app-rail-bus-table',
  imports: [LocalDatePipe],
  templateUrl: './rail-bus-table.html',
  styleUrl: './rail-bus-table.scss',
})
export class RailBusTable {
  protected readonly t = t;
  readonly comparison = input.required<RailBusComparison>();

  /** The pairs, each "from" station's rows together, its name on the first of them. */
  protected readonly rows = computed(() => {
    const pairs = this.comparison().pairs;
    return pairs.map((pair, i) => ({
      ...pair,
      span:
        i > 0 && pairs[i - 1].from === pair.from
          ? 0
          : pairs.filter((p) => p.from === pair.from).length,
      faster: faster(pair.train, pair.bus),
      difference: pair.train && pair.bus ? pair.bus.minutes - pair.train.minutes : null,
    }));
  });

  /** The routes taken, the first departure and the last arrival: "20 → 8 · 09:04–09:19". */
  protected summary(journey: RailBusJourney): string {
    const routes = journey.legs.map((l) => l.route).join(' → ');
    return `${routes} · ${journey.departure}–${journey.arrival}`;
  }

  /** Every ride, one a route, for the tooltip. */
  protected legs(journey: RailBusJourney): string {
    return journey.legs.map((l) => t('cityRail.vsBus.leg', { ...l })).join('\n');
  }

  /** "+12 min", "−3 min" or "±0 min". */
  protected signed(minutes: number): string {
    const sign = minutes > 0 ? '+' : minutes < 0 ? '−' : '±';
    return sign + t('cityRail.vsBus.min', { n: Math.abs(minutes) });
  }
}

function faster(train: RailBusJourney | null, bus: RailBusJourney | null): Faster {
  if (!train || !bus) return train ? 'train' : bus ? 'bus' : null;
  return train.minutes < bus.minutes ? 'train' : bus.minutes < train.minutes ? 'bus' : null;
}
