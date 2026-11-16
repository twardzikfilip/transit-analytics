import { DestroyRef, ElementRef, Signal, inject, signal } from '@angular/core';

/**
 * The host element's width in px (at least `min`), kept up to date as it resizes, for charts that draw to fit. Call
 * it in a field initializer or constructor (it needs the injection context).
 */
export function hostWidth(initial: number, min: number): Signal<number> {
  const width = signal(initial);
  if (typeof ResizeObserver !== 'undefined') {
    const host = inject(ElementRef<HTMLElement>);
    const observer = new ResizeObserver(([entry]) =>
      width.set(Math.max(min, Math.floor(entry.contentRect.width))),
    );
    observer.observe(host.nativeElement);
    inject(DestroyRef).onDestroy(() => observer.disconnect());
  }
  return width.asReadonly();
}
