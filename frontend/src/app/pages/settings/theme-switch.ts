import { Component, inject } from '@angular/core';
import { Theme, ThemeName } from '../../core/theme/theme';
import { Icon } from '../../shared/icons/icon';
import { t } from '../../core/i18n/t';

/** Light or dark theme: two buttons, the chosen one pressed. */
@Component({
  selector: 'app-theme-switch',
  imports: [Icon],
  template: `
    <div class="switch" role="group" [attr.aria-label]="t('settings.theme')">
      @for (o of options; track o.value) {
        <button
          type="button"
          [class.active]="theme.current() === o.value"
          [attr.aria-pressed]="theme.current() === o.value"
          (click)="theme.set(o.value)"
        >
          <app-icon [name]="o.icon" />
          {{ t(o.label) }}
        </button>
      }
    </div>
  `,
  styles: `
    .switch {
      display: inline-flex;
      gap: 0.5rem;
    }
    button {
      display: inline-flex;
      align-items: center;
      gap: 0.5rem;
    }
  `,
})
export class ThemeSwitch {
  protected readonly t = t;
  protected readonly theme = inject(Theme);
  protected readonly options: { value: ThemeName; label: string; icon: 'sun' | 'moon' }[] = [
    { value: 'light', label: 'settings.light', icon: 'sun' },
    { value: 'dark', label: 'settings.dark', icon: 'moon' },
  ];
}
