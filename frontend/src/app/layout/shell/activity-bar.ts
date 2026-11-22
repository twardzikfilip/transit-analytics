import { ChangeDetectionStrategy, Component } from '@angular/core';
import { t } from '../../core/i18n/t';

/** The progress bar under the header while the app waits for the backend (see core/api/activity.ts). */
@Component({
  selector: 'app-activity-bar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { role: 'progressbar', '[attr.aria-label]': 'label()' },
  template: '<span></span>',
  styleUrl: './activity-bar.scss',
})
export class ActivityBar {
  protected readonly label = () => t('nav.loadingData');
}
