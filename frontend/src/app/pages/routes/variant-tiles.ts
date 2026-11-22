import { Component, computed, input } from '@angular/core';
import { LineDetail, TimePeriod, VariantKind } from '../../core/models/models';
import { regularityGrade, regularityMeaning } from '../../shared/regularity';
import { LocalNumberPipe } from '../../shared/i18n/locale-pipes';
import { t, tn } from '../../core/i18n/t';

/**
 * How evenly a line's departures are spread on a weekday, in peak and off-peak hours: the coefficient of variation
 * of the gaps between them (0 = perfectly even), null with too few departures; and when those hours are.
 */
export interface LineRegularity {
  peakCv: number | null;
  offPeakCv: number | null;
  peak: TimePeriod[];
  offPeak: TimePeriod[];
}

/**
 * The line's summary tiles: length, scheduled travel time and speed (base route, then every variant), zone 2,
 * brigades, and how regular its departures are in peak and off-peak hours.
 */
@Component({
  selector: 'app-variant-tiles',
  imports: [LocalNumberPipe],
  templateUrl: './variant-tiles.html',
  styleUrl: './variant-tiles.scss',
})
export class VariantTiles {
  protected readonly t = t;
  readonly detail = input.required<LineDetail>();
  /** The line's regularity; null while it loads or for a line without weekday statistics. */
  readonly regularity = input<LineRegularity | null>(null);

  protected readonly grade = regularityGrade;
  protected readonly meaning = regularityMeaning;
  /** "06:30–08:30, 15:00–17:00" */
  protected periods(periods: TimePeriod[]): string {
    return periods.map((p) => `${p.from}–${p.to}`).join(', ');
  }

  /** Short kind label for the tile lists. */
  protected readonly shortKind = (kind: VariantKind) => t(`tiles.kind.${kind}`);

  protected readonly trips = computed(() =>
    this.detail().variants.reduce((n, v) => n + v.tripsPerDay, 0),
  );

  /** Zone 2 entries of the base route first, then of longer variants. */
  protected readonly zone2 = computed(() => {
    const zones = this.detail().zone2;
    return {
      base: zones.filter((z) => z.kind === 'main'),
      longer: zones.filter((z) => z.kind !== 'main'),
    };
  });
}
