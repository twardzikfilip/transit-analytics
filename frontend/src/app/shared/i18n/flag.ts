import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { Lang } from '../../core/i18n/t';

/** A small flag for a language: the United Kingdom's for English, Poland's and Germany's. Decorative. */
@Component({
  selector: 'app-flag',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { 'aria-hidden': 'true' },
  template: `
    @switch (lang()) {
      @case ('pl') {
        <svg viewBox="0 0 16 10" preserveAspectRatio="xMidYMid slice">
          <rect width="16" height="5" fill="#fff" />
          <rect y="5" width="16" height="5" fill="#dc143c" />
        </svg>
      }
      @case ('de') {
        <svg viewBox="0 0 15 9" preserveAspectRatio="xMidYMid slice">
          <rect width="15" height="3" fill="#000" />
          <rect y="3" width="15" height="3" fill="#dd0000" />
          <rect y="6" width="15" height="3" fill="#ffce00" />
        </svg>
      }
      @default {
        <svg viewBox="0 0 60 30" preserveAspectRatio="xMidYMid slice">
          <clipPath id="flag-gb-clip"><path d="M0 0v30h60V0z" /></clipPath>
          <clipPath id="flag-gb-diag"><path d="M30 15h30v15zv15H0zH0V0zV0h30z" /></clipPath>
          <g clip-path="url(#flag-gb-clip)">
            <path d="M0 0v30h60V0z" fill="#012169" />
            <path d="M0 0l60 30m0-30L0 30" stroke="#fff" stroke-width="6" />
            <path
              d="M0 0l60 30m0-30L0 30"
              clip-path="url(#flag-gb-diag)"
              stroke="#c8102e"
              stroke-width="4"
            />
            <path d="M30 0v30M0 15h60" stroke="#fff" stroke-width="10" />
            <path d="M30 0v30M0 15h60" stroke="#c8102e" stroke-width="6" />
          </g>
        </svg>
      }
    }
  `,
  styles: `
    :host {
      display: inline-flex;
      width: 1.5em;
      height: 1em;
      flex-shrink: 0;
      border-radius: 2px;
      overflow: hidden;
      box-shadow: 0 0 0 1px rgb(0 0 0 / 15%);
    }

    svg {
      width: 100%;
      height: 100%;
    }
  `,
})
export class Flag {
  readonly lang = input.required<Lang>();
}
