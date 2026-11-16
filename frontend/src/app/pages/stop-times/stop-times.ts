import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Store } from '@ngrx/store';
import { boardingRule, formatGtfsTime, routeColor } from '../../core/gtfs';
import { StopTime } from '../../core/models/models';
import { Pager } from '../../shared/pager/pager';
import { listQuery } from '../../shared/list-query';
import { StopTimesActions } from '../../store/stop-times/stop-times.actions';
import { stopTimesFeature } from '../../store/stop-times/stop-times.reducer';

@Component({
  selector: 'app-stop-times',
  imports: [RouterLink, Pager],
  templateUrl: './stop-times.html',
})
export class StopTimes {
  private readonly store = inject(Store);

  protected readonly stopTimes = this.store.selectSignal(stopTimesFeature.selectAll);
  protected readonly page = this.store.selectSignal(stopTimesFeature.selectPage);
  protected readonly loading = this.store.selectSignal(stopTimesFeature.selectLoading);
  protected readonly error = this.store.selectSignal(stopTimesFeature.selectError);

  protected readonly query = listQuery({
    filters: ['tripId', 'stopId'],
    size: 50,
    // One trip: in stop order. One stop: a departure board. Otherwise: as stored.
    sort: (f) => (f['tripId'] ? 'stopSequence' : f['stopId'] ? ['departureTime', 'id'] : 'id'),
    onChange: (query) => this.store.dispatch(StopTimesActions.load({ query })),
  });

  /** Names for the filter chips, taken from the loaded rows. */
  protected readonly first = computed<StopTime | undefined>(() => this.stopTimes()[0]);
  protected readonly title = computed(() => {
    const first = this.first();
    if (this.query.get('tripId')) return first ? `Trip ${first.routeName} → ${first.tripHeadsign ?? ''}` : 'Trip';
    if (this.query.get('stopId')) return first ? `Departures from ${first.stopName}` : 'Departures';
    return 'Stop times';
  });

  protected readonly formatTime = formatGtfsTime;
  protected readonly routeColor = routeColor;

  protected boarding(st: StopTime): string | null {
    const pickup = boardingRule(st.pickupType);
    const dropOff = boardingRule(st.dropOffType);
    if (pickup && pickup === dropOff) return `Stops ${pickup}`;
    return [pickup && `boarding ${pickup}`, dropOff && `alighting ${dropOff}`].filter(Boolean).join(', ') || null;
  }

  protected index(i: number): number {
    const p = this.page();
    return (p ? p.number * p.size : 0) + i + 1;
  }
}
