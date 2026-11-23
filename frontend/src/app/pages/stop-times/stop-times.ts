import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Store } from '@ngrx/store';
import { boardingRule, formatGtfsTime } from '../../core/gtfs';
import { StopTime } from '../../core/models/models';
import { Pager, rowNumber } from '../../shared/pager/pager';
import { listQuery } from '../../shared/list-query';
import { StopTimesActions } from '../../store/stop-times/stop-times.actions';
import { stopTimesFeature } from '../../store/stop-times/stop-times.reducer';
import { Skeleton } from '../../shared/skeleton/skeleton';
import { t } from '../../core/i18n/t';
import { LineBadge } from '../../shared/line-kind/line-kinds';

@Component({
  selector: 'app-stop-times',
  imports: [LineBadge, Skeleton, RouterLink, Pager],
  templateUrl: './stop-times.html',
})
export class StopTimes {
  protected readonly t = t;
  private readonly store = inject(Store);

  protected readonly stopTimes = this.store.selectSignal(stopTimesFeature.selectAll);
  protected readonly page = this.store.selectSignal(stopTimesFeature.selectPage);
  protected readonly loading = this.store.selectSignal(stopTimesFeature.selectLoading);

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
    if (this.query.get('tripId'))
      return first
        ? t('timetable.titleTrip', { route: first.routeName, to: first.tripHeadsign ?? '' })
        : t('timetable.titleTripShort');
    if (this.query.get('stopId'))
      return first
        ? t('timetable.titleDepartures', { stop: first.stopName })
        : t('timetable.departures');
    return t('timetable.stopTimes');
  });

  protected readonly formatTime = formatGtfsTime;

  protected boarding(st: StopTime): string | null {
    const pickup = boardingRule(st.pickupType);
    const dropOff = boardingRule(st.dropOffType);
    if (pickup && pickup === dropOff) return t('timetable.stops', { rule: pickup });
    return (
      [
        pickup && t('timetable.boarding', { rule: pickup }),
        dropOff && t('timetable.alighting', { rule: dropOff }),
      ]
        .filter(Boolean)
        .join(', ') || null
    );
  }

  protected index(i: number): number {
    return rowNumber(this.page(), i);
  }
}
