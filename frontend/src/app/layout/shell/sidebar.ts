import { Component, input, output, signal } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { Icon } from '../../shared/icons/icon';
import { IconName } from '../../shared/icons/icons';
import { t } from '../../core/i18n/t';

interface NavItem {
  path: string;
  /** Translation key of its name. */
  label: string;
  icon: IconName;
}

interface NavGroup {
  /** Its heading's translation key; null: no heading (Home, on its own at the top). */
  label: string | null;
  items: NavItem[];
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

  /** The sidebar: Home on its own, then day-to-day operation, then the network and its timetable. */
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
  ];

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
