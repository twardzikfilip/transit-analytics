import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { DomSanitizer } from '@angular/platform-browser';
import { ICONS, IconName } from './icons';

/** One of the app's icons ({@link ICONS}), 1em square in the text colour; decorative, so hidden from screen readers. */
@Component({
  selector: 'app-icon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<svg
    viewBox="0 0 16 16"
    fill="currentColor"
    aria-hidden="true"
    [innerHTML]="markup()"
  ></svg>`,
  styles: `
    :host {
      display: inline-flex;
      width: 1em;
      height: 1em;
      flex-shrink: 0;
    }
    svg {
      width: 100%;
      height: 100%;
    }
  `,
})
export class Icon {
  private readonly sanitizer = inject(DomSanitizer);
  readonly name = input.required<IconName>();
  // The markup is a constant of this app, never user input.
  protected readonly markup = computed(() =>
    this.sanitizer.bypassSecurityTrustHtml(ICONS[this.name()]),
  );
}
