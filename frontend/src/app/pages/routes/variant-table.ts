import { DecimalPipe } from '@angular/common';
import { Component, input } from '@angular/core';
import { LineVariant } from '../../core/models/models';
import { variantKindLabel } from './format';

/** A line's variants: each one's ends, length, trips, scheduled travel time and speed; the main ones in bold. */
@Component({
  selector: 'app-variant-table',
  imports: [DecimalPipe],
  templateUrl: './variant-table.html',
  styles: `
    tr.main td:first-child {
      font-weight: 600;
    }
  `,
})
export class VariantTable {
  readonly variants = input.required<LineVariant[]>();

  protected readonly kindLabel = variantKindLabel;
}
