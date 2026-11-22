import { DestroyRef, Injectable, inject, signal } from '@angular/core';

/** Where the header's back button leads, and what it says to screen readers (a translation key). */
export interface BackLink {
  label: string;
  go: () => void;
}

/** The back button in the header (left of the page title), set by the page shown; none on most pages. */
@Injectable({ providedIn: 'root' })
export class PageBack {
  readonly link = signal<BackLink | null>(null);
}

/**
 * Shows the header's back button while this page is shown. Call in a page's injection context (a field initializer
 * or its constructor).
 */
export function injectBackLink(label: string, go: () => void): void {
  const back = inject(PageBack);
  const link: BackLink = { label, go };
  back.link.set(link);
  // The next page may already have set its own: only take away this page's.
  inject(DestroyRef).onDestroy(() => {
    if (back.link() === link) back.link.set(null);
  });
}
