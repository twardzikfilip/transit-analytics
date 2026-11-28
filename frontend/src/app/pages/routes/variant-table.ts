import { Component, input } from '@angular/core';
import { RouteVariant } from '../../core/models/models';
import { variantKindLabel } from './format';
import { LocalNumberPipe } from '../../shared/i18n/locale-pipes';
import { t } from '../../core/i18n/t';

/** A route's variants: each one's ends, length, trips, scheduled travel time and speed; the main ones in bold. */
@Component({
  selector: 'app-variant-table',
  imports: [LocalNumberPipe],
  templateUrl: './variant-table.html',
  styles: `
    tr.main td:first-child {
      font-weight: 600;
    }
  `,
})
export class VariantTable {
  protected readonly t = t;
  readonly variants = input.required<RouteVariant[]>();

  protected readonly kindLabel = variantKindLabel;
}
