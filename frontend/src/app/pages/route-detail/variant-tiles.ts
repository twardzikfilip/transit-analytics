import { DecimalPipe } from '@angular/common';
import { Component, computed, input } from '@angular/core';
import { LineDetail, VariantKind } from '../../core/models/models';

/**
 * The line's summary tiles: length, scheduled travel time and speed (base route, then every variant), zone 2 and
 * brigades.
 */
@Component({
  selector: 'app-variant-tiles',
  imports: [DecimalPipe],
  templateUrl: './variant-tiles.html',
  styleUrl: './variant-tiles.scss',
})
export class VariantTiles {
  readonly detail = input.required<LineDetail>();

  /** Short kind label for the tile lists. */
  protected readonly shortKind = (kind: VariantKind) =>
    ({ main: 'Base', longer: 'Longer', shorter: 'Shorter', depot: 'Depot' })[kind];

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
