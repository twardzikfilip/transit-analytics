import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { TripAnomalyApi } from '../../core/api/api.services';
import { FaultyTracker, FaultyTrackers as Report } from '../../core/models/models';
import { RouteBadge } from '../../shared/route-kind/route-kinds';
import { Skeleton } from '../../shared/skeleton/skeleton';
import { LocalDatePipe } from '../../shared/i18n/locale-pipes';
import { shareOf } from '../../shared/format/number';
import { t, tn } from '../../core/i18n/t';

/** "46 of 76 trips (61%)". */
export function trackerTrips(f: FaultyTracker): string {
  return t('home.trackers.trips', {
    n: f.noSignalTrips,
    of: f.trips,
    share: shareOf(f.noSignalTrips, f.trips, { digits: 0 }),
  });
}

/**
 * Buses whose tracker probably fails: over the last week, on several days, they stopped reporting partway through
 * their trips and were back later. Worth checking regularly, so it has a red border while any bus is listed.
 */
@Component({
  selector: 'app-faulty-trackers',
  imports: [RouterLink, RouteBadge, Skeleton, LocalDatePipe],
  template: `
    @if (report(); as r) {
      <details
        class="card tile"
        [class.alert]="r.trackers.length"
        [attr.aria-label]="t('home.trackers.title')"
      >
        <summary>
          <h2>
            {{ t('home.trackers.title') }}
            @if (r.trackers.length) {
              <span class="count">{{ r.trackers.length }}</span>
            }
          </h2>
        </summary>
        <p class="muted rule">
          {{
            t('home.trackers.rule', {
              days: r.minDays,
              trips: r.minTrips,
              share: r.minSharePercent,
            })
          }}
        </p>
        @if (r.trackers.length) {
          <ul>
            @for (f of r.trackers; track f.vehicleId) {
              <li>
                <a class="bus" [routerLink]="['/vehicles', f.vehicleId]">{{ f.bus }}</a>
                <span>{{ trips(f) }}</span>
                <span class="muted">{{ tn('home.trackers.days', f.days) }}</span>
                <span class="lines">
                  @for (l of f.routes; track l) {
                    <span class="route-badge" [appRouteBadge]="l">{{ l }}</span>
                  }
                </span>
                <span class="muted">{{
                  t('home.trackers.last', { when: f.lastNoSignal | date: 'EEE d MMM, HH:mm' })
                }}</span>
              </li>
            }
          </ul>
        } @else {
          <p class="muted">{{ t('home.trackers.none') }}</p>
        }
      </details>
    } @else if (loading()) {
      <section class="card tile">
        <app-skeleton kind="lines" [count]="3" [label]="t('home.trackers.loading')" />
      </section>
    }
  `,
  styles: `
    .tile {
      margin-bottom: 1.5rem;
      border: 2px solid transparent;
    }

    .tile.alert {
      border-color: var(--danger);
    }

    /* Folded by default: only the title and the count show until it is opened. */
    summary {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      cursor: pointer;
      list-style: none;
    }

    summary::-webkit-details-marker {
      display: none;
    }

    summary::before {
      content: '▸';
      color: var(--muted);
      transition: transform 0.15s;
    }

    details[open] > summary::before {
      transform: rotate(90deg);
    }

    details[open] > summary {
      margin-bottom: 0.5rem;
    }

    h2 {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      margin: 0;
      font-size: 1.05rem;
    }

    .count {
      padding: 0 0.5rem;
      border-radius: 999px;
      background: var(--danger);
      color: #fff;
      font-size: 0.85rem;
    }

    .rule {
      margin: 0 0 0.5rem;
      font-size: 0.85rem;
    }

    ul {
      margin: 0;
      padding: 0;
      list-style: none;
    }

    li {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.25rem 0.75rem;
      padding: 0.35rem 0;
      border-top: 1px solid var(--border);
      font-size: 0.9rem;
    }

    .bus {
      min-width: 3.5rem;
      font-weight: 700;
    }

    .lines {
      display: inline-flex;
      flex-wrap: wrap;
      gap: 0.25rem;
    }
  `,
})
export class FaultyTrackers {
  protected readonly t = t;
  protected readonly tn = tn;
  protected readonly trips = trackerTrips;

  /** Null while loading, and after a failure (shown as a toast). */
  private readonly state = toSignal(
    inject(TripAnomalyApi)
      .faultyTrackers()
      .pipe(
        map((report) => ({ report, loading: false })),
        catchError(() => of({ report: null as Report | null, loading: false })),
      ),
    { initialValue: { report: null as Report | null, loading: true } },
  );
  protected readonly report = () => this.state().report;
  protected readonly loading = () => this.state().loading;
}
