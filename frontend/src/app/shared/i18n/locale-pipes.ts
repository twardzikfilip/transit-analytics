import { formatDate, formatNumber } from '@angular/common';
import { Pipe, PipeTransform } from '@angular/core';
import { angularLocale } from '../../core/i18n/t';

/**
 * Angular's `date` pipe in the language shown ("Sat 21 Nov" / "sob. 21 lis" / "Sa., 21. Nov."). Not pure: it formats
 * again when the language changes.
 */
@Pipe({ name: 'date', pure: false })
export class LocalDatePipe implements PipeTransform {
  transform(
    value: string | number | Date | null | undefined,
    format = 'mediumDate',
    timezone?: string,
  ): string | null {
    if (value === null || value === undefined || value === '') return null;
    return formatDate(value, format, angularLocale(), timezone);
  }
}

/** Angular's `number` pipe in the language shown ("1,234.5" / "1 234,5"). */
@Pipe({ name: 'number', pure: false })
export class LocalNumberPipe implements PipeTransform {
  transform(value: number | string | null | undefined, digitsInfo?: string): string | null {
    if (value === null || value === undefined || value === '') return null;
    return formatNumber(Number(value), angularLocale(), digitsInfo);
  }
}
