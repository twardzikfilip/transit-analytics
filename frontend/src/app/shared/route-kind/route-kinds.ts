import { Directive, Injectable, computed, inject, input, signal } from '@angular/core';
import { RouteDetailApi } from '../../core/api/api.services';
import { RouteSubtype } from '../../core/models/models';
import { ROUTE_KIND_COLORS, ROUTE_TEXT_COLOR, cssColor } from '../palette';

/**
 * A route's kind by its name alone, as the backend's default: N1… night, 400–499 and 700 regional, other numbers from
 * 100 up suburban, everything else urban.
 */
export function classifyRoute(route: string): RouteSubtype {
  const name = route.trim();
  if (/^N\d+$/i.test(name)) return 'night_urban';
  if (/^0*(4\d{2}|700)$/.test(name)) return 'regional';
  if (/^0*[1-9]\d{2,}$/.test(name)) return 'suburban';
  return 'urban';
}

/**
 * Every route's kind, from the timetable (asked for once, on first use), so a route label anywhere can take its kind's
 * colour: indigo urban, green suburban, blue regional, black night routes. A route not in the timetable is classified
 * by its name. The kinds are asked for once, when first needed.
 */
@Injectable({ providedIn: 'root' })
export class RouteKinds {
  private readonly api = inject(RouteDetailApi);
  private readonly stored = signal<Record<string, RouteSubtype>>({});

  constructor() {
    // A failure leaves the kinds by name (a toast tells of it).
    this.api.routes().subscribe({
      next: (routes) => this.stored.set(Object.fromEntries(routes.map((l) => [l.name, l.subtype]))),
      error: () => undefined,
    });
  }

  kind(route: string | null | undefined): RouteSubtype | null {
    if (!route) return null;
    return this.stored()[route.trim()] ?? classifyRoute(route);
  }

  /** The kind's colour token, for CSS: "var(--line-urban)". */
  cssVar(route: string | null | undefined): string | null {
    const kind = this.kind(route);
    return kind ? `var(${ROUTE_KIND_COLORS[kind].token})` : null;
  }

  /** The kind's colour as a value, for maps and other code that can't use CSS custom properties. */
  color(route: string | null | undefined): string | null {
    const kind = this.kind(route);
    return kind ? cssColor(ROUTE_KIND_COLORS[kind].token, ROUTE_KIND_COLORS[kind].fallback) : null;
  }
}

/**
 * Colours a route label (`.route-badge`) by its route's kind: `<span class="route-badge" [appLineBadge]="'103'">`.
 */
@Directive({
  selector: '[appRouteBadge]',
  host: {
    '[style.--route-color]': 'color()',
    '[style.--route-text-color]': 'textColor',
  },
})
export class RouteBadge {
  private readonly kinds = inject(RouteKinds);
  readonly appRouteBadge = input<string | null | undefined>(null);
  protected readonly color = computed(() => this.kinds.cssVar(this.appRouteBadge()));
  protected readonly textColor = ROUTE_TEXT_COLOR;
}
