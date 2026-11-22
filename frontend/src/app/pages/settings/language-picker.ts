import { Component, ElementRef, computed, inject, signal } from '@angular/core';
import { LANGUAGE_OPTIONS, Language, LanguageOption } from '../../core/i18n/language';
import { t } from '../../core/i18n/t';
import { Flag } from '../../shared/i18n/flag';

/**
 * The language of the app: a dropdown of the languages, each with its flag and its name in its own language
 * ("Polski", "Deutsch"). A listbox (not a <select>, which can't show flags): arrows move, Enter chooses, Escape
 * closes.
 */
@Component({
  selector: 'app-language-picker',
  imports: [Flag],
  host: {
    '(document:click)': 'closeOutside($event)',
  },
  template: `
    <button
      type="button"
      class="current"
      aria-haspopup="listbox"
      [attr.aria-expanded]="open()"
      [attr.aria-label]="t('settings.language') + ': ' + chosen().name"
      (click)="toggle()"
      (keydown.arrowDown)="openAt($event, 0)"
    >
      <app-flag [lang]="chosen().code" />
      <span class="name">{{ chosen().name }}</span>
      <span class="caret" aria-hidden="true">▾</span>
    </button>
    @if (open()) {
      <ul
        class="options"
        role="listbox"
        tabindex="-1"
        [attr.aria-label]="t('settings.language')"
        [attr.aria-activedescendant]="'language-' + options[focused()].code"
        (keydown)="key($event)"
      >
        @for (o of options; track o.code; let i = $index) {
          <li
            role="option"
            [id]="'language-' + o.code"
            [attr.aria-selected]="o.code === chosen().code"
            [attr.lang]="o.code"
            [class.focused]="i === focused()"
            (click)="choose(o)"
            (pointerenter)="focused.set(i)"
          >
            <app-flag [lang]="o.code" />
            <span class="name">{{ o.name }}</span>
            @if (o.code === chosen().code) {
              <span class="check" aria-hidden="true">✓</span>
            }
          </li>
        }
      </ul>
    }
  `,
  styles: `
    :host {
      position: relative;
      display: inline-block;
    }

    .current {
      display: inline-flex;
      align-items: center;
      gap: 0.5rem;
      min-width: 11rem;
    }

    .caret {
      margin-left: auto;
      font-size: 0.8em;
      color: var(--muted);
    }

    .options {
      position: absolute;
      z-index: 20;
      top: calc(100% + 4px);
      left: 0;
      min-width: 100%;
      margin: 0;
      padding: 0.25rem;
      list-style: none;
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: var(--radius);
      box-shadow: var(--shadow-float);
      outline: none;
    }

    li {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.45rem 0.6rem;
      border-radius: calc(var(--radius) - 2px);
      cursor: pointer;

      &.focused {
        background: var(--hover);
      }

      &[aria-selected='true'] {
        font-weight: 600;
      }
    }

    .check {
      margin-left: auto;
      color: var(--link);
    }
  `,
})
export class LanguagePicker {
  private readonly language = inject(Language);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly t = t;
  protected readonly options = LANGUAGE_OPTIONS;
  protected readonly chosen = computed(
    () => LANGUAGE_OPTIONS.find((o) => o.code === this.language.current()) ?? LANGUAGE_OPTIONS[0],
  );
  protected readonly open = signal(false);
  /** The option the arrows are on. */
  protected readonly focused = signal(0);

  protected toggle(): void {
    if (this.open()) {
      this.open.set(false);
    } else {
      this.openAt(null, this.options.indexOf(this.chosen()));
    }
  }

  protected openAt(event: Event | null, index: number): void {
    event?.preventDefault();
    this.focused.set(index);
    this.open.set(true);
    // The list takes the keys once it is drawn.
    setTimeout(() => this.host.nativeElement.querySelector<HTMLElement>('.options')?.focus());
  }

  protected key(event: KeyboardEvent): void {
    const last = this.options.length - 1;
    if (event.key === 'ArrowDown') this.focused.set(Math.min(last, this.focused() + 1));
    else if (event.key === 'ArrowUp') this.focused.set(Math.max(0, this.focused() - 1));
    else if (event.key === 'Home') this.focused.set(0);
    else if (event.key === 'End') this.focused.set(last);
    else if (event.key === 'Enter' || event.key === ' ') this.choose(this.options[this.focused()]);
    else if (event.key === 'Escape') this.close();
    else if (event.key === 'Tab') return this.open.set(false);
    else return;
    event.preventDefault();
  }

  protected choose(option: LanguageOption): void {
    this.language.set(option.code);
    this.close();
  }

  protected closeOutside(event: Event): void {
    if (this.open() && !this.host.nativeElement.contains(event.target as Node))
      this.open.set(false);
  }

  private close(): void {
    this.open.set(false);
    this.host.nativeElement.querySelector<HTMLButtonElement>('.current')?.focus();
  }
}
