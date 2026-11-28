import { Component, inject, input, output, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { filter, map } from 'rxjs';
import { Icon } from '../../shared/icons/icon';
import { IconName } from '../../shared/icons/icons';
import { t } from '../../core/i18n/t';
import { APP_VERSION } from '../../core/version';

interface NavItem {
  path: string;
  /** Translation key of its name. */
  label: string;
  icon: IconName;
  /** Its pages, in a submenu that drops down under it (the item itself then only opens and closes it). */
  children?: { path: string; label: string }[];
}

interface NavGroup {
  /** Its heading's translation key; null: no heading (Home, on its own at the top). */
  label: string | null;
  items: NavItem[];
  /** A route above it. */
  divider?: boolean;
}

/**
 * The sidebar: the pages, grouped, each with its icon. On a desktop it can be narrowed to its icons (the names show as
 * tooltips; remembered in this browser); on a phone it is hidden to the left until the header's button shows it.
 */
@Component({
  selector: 'app-sidebar',
  imports: [RouterLink, RouterLinkActive, Icon],
  templateUrl: './sidebar.html',
  styleUrl: './sidebar.scss',
})
export class Sidebar {
  /** Shown over the page (a phone). */
  readonly open = input(false);
  /** Asks to hide it again (a phone): a page chosen, or its close button. */
  readonly close = output<void>();

  /** On a desktop, narrowed to its icons; remembered in this browser. */
  protected readonly collapsed = signal(readCollapsed());
  protected readonly t = t;
  protected readonly version = APP_VERSION;

  /** The sidebar: Home on its own, then day-to-day operation, then the network and its timetable, then the trains. */
  protected readonly navGroups: NavGroup[] = [
    { label: null, items: [{ path: '/home', label: 'nav.home', icon: 'home' }] },
    {
      label: 'nav.operation',
      items: [
        { path: '/brigades', label: 'nav.brigades', icon: 'brigades' },
        { path: '/vehicles', label: 'nav.fleet', icon: 'fleet' },
        { path: '/trips', label: 'nav.trips', icon: 'trips' },
        { path: '/trip-anomalies', label: 'nav.tripAnomalies', icon: 'tripAnomalies' },
      ],
    },
    {
      label: 'nav.network',
      items: [
        { path: '/hotspots', label: 'nav.hotspots', icon: 'hotspots' },
        { path: '/bus-lanes', label: 'nav.busLanes', icon: 'busLanes' },
        { path: '/delay-generation', label: 'nav.delayGeneration', icon: 'delayGeneration' },
        { path: '/routes', label: 'nav.routes', icon: 'routes' },
        { path: '/corridors', label: 'nav.corridors', icon: 'corridors' },
        { path: '/network-coverage', label: 'nav.networkCoverage', icon: 'networkCoverage' },
        { path: '/stops', label: 'nav.stops', icon: 'stops' },
        { path: '/stop-times', label: 'nav.stopTimes', icon: 'stopTimes' },
      ],
    },
    {
      label: null,
      divider: true,
      items: [
        {
          path: '/city-rail',
          label: 'nav.cityRail',
          icon: 'cityRail',
          children: [
            { path: '/city-rail/current', label: 'nav.cityRailCurrent' },
            { path: '/city-rail/proposal-1', label: 'nav.cityRailProposal' },
          ],
        },
      ],
    },
  ];

  private readonly router = inject(Router);
  /** The page shown. */
  private readonly url = toSignal(
    this.router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      map(() => this.router.url),
    ),
    { initialValue: this.router.url },
  );
  /** Submenus opened or closed by hand; the others are open while one of their pages is shown. */
  private readonly toggled = signal<Record<string, boolean>>({});

  /** Whether one of an item's pages is shown. */
  protected within(item: NavItem): boolean {
    return this.url().startsWith(item.path + '/') || this.url() === item.path;
  }

  protected expanded(item: NavItem): boolean {
    return this.toggled()[item.path] ?? this.within(item);
  }

  protected toggle(item: NavItem): void {
    const open = !this.expanded(item);
    this.toggled.update((t) => ({ ...t, [item.path]: open }));
  }

  protected toggleCollapsed(): void {
    this.collapsed.update((c) => !c);
    try {
      localStorage.setItem(COLLAPSED_KEY, String(this.collapsed()));
    } catch {
      // Not remembered (private window, blocked storage); it still applies until the page is closed.
    }
  }
}

const COLLAPSED_KEY = 'sidebar-collapsed';

/** Whether the sidebar was left narrowed; not when storage is unavailable. */
function readCollapsed(): boolean {
  try {
    return localStorage.getItem(COLLAPSED_KEY) === 'true';
  } catch {
    return false;
  }
}
