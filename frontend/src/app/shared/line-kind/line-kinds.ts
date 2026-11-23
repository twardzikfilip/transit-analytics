import { Directive, Injectable, computed, inject, input, signal } from '@angular/core';
import { LineDetailApi } from '../../core/api/api.services';
import { RouteSubtype } from '../../core/models/models';
import { cssColor } from '../palette';

/** Each kind of line's colour: a token in styles.scss (light and dark), and its value before styles load. */
export const LINE_KIND_COLORS: Record<RouteSubtype, { token: string; fallback: string }> = {
  urban: { token: '--line-urban', fallback: '#4b0082' },
  suburban: { token: '--line-suburban', fallback: '#03bf03' },
  regional: { token: '--line-regional', fallback: '#0021ff' },
  night_urban: { token: '--line-night', fallback: '#111111' },
};

/**
 * A line's kind by its name alone, as the backend's default: N1… night, 400–499 and 700 regional, other numbers from
 * 100 up suburban, everything else urban.
 */
export function classifyLine(line: string): RouteSubtype {
  const name = line.trim();
  if (/^N\d+$/i.test(name)) return 'night_urban';
  if (/^0*(4\d{2}|700)$/.test(name)) return 'regional';
  if (/^0*[1-9]\d{2,}$/.test(name)) return 'suburban';
  return 'urban';
}

/**
 * Every line's kind, from the timetable (asked for once, on first use), so a line label anywhere can take its kind's
 * colour: indigo urban, green suburban, blue regional, black night lines. A line not in the timetable is classified
 * by its name. The kinds are asked for once, when first needed.
 */
@Injectable({ providedIn: 'root' })
export class LineKinds {
  private readonly api = inject(LineDetailApi);
  private readonly stored = signal<Record<string, RouteSubtype>>({});

  constructor() {
    // A failure leaves the kinds by name (a toast tells of it).
    this.api
      .subtypes()
      .subscribe({ next: (kinds) => this.stored.set(kinds), error: () => undefined });
  }

  kind(line: string | null | undefined): RouteSubtype | null {
    if (!line) return null;
    return this.stored()[line.trim()] ?? classifyLine(line);
  }

  /** The kind's colour token, for CSS: "var(--line-urban)". */
  cssVar(line: string | null | undefined): string | null {
    const kind = this.kind(line);
    return kind ? `var(${LINE_KIND_COLORS[kind].token})` : null;
  }

  /** The kind's colour as a value, for maps and other code that can't use CSS custom properties. */
  color(line: string | null | undefined): string | null {
    const kind = this.kind(line);
    return kind ? cssColor(LINE_KIND_COLORS[kind].token, LINE_KIND_COLORS[kind].fallback) : null;
  }
}

/**
 * Colours a line label (`.route-badge`) by its line's kind: `<span class="route-badge" [appLineBadge]="'103'">`.
 */
@Directive({
  selector: '[appLineBadge]',
  host: {
    '[style.--route-color]': 'color()',
    '[style.--route-text-color]': '"#fff"',
  },
})
export class LineBadge {
  private readonly kinds = inject(LineKinds);
  readonly appLineBadge = input<string | null | undefined>(null);
  protected readonly color = computed(() => this.kinds.cssVar(this.appLineBadge()));
}
