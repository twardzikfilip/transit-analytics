import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { NgxSkeletonLoaderComponent } from 'ngx-skeleton-loader';
import { t } from '../../core/i18n/t';

/** The shapes a page's content can take while it loads. */
export type SkeletonKind = 'tiles' | 'chart' | 'map' | 'table' | 'lines' | 'chips' | 'card';

/**
 * A placeholder shaped like what is loading (tiles, a chart, a map, a table, text, a card), shimmering until the
 * data arrives; drawn with ngx-skeleton-loader, in the theme's colours (--ngx-skeleton-loader-* in styles.scss).
 * Announced once to screen readers as "Loading".
 */
@Component({
  selector: 'app-skeleton',
  imports: [NgxSkeletonLoaderComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { role: 'status', 'aria-busy': 'true', '[attr.aria-label]': "label() || t('nav.loading')" },
  template: `
    @switch (kind()) {
      @case ('tiles') {
        <div class="tiles">
          @for (i of items(); track i) {
            <div class="card tile">
              <ngx-skeleton-loader [theme]="{ width: '45%', height: '0.8rem' }" />
              <ngx-skeleton-loader [theme]="{ width: '35%', height: '1.8rem' }" />
              <ngx-skeleton-loader [theme]="{ width: '75%', height: '0.7rem', margin: 0 }" />
            </div>
          }
        </div>
      }
      @case ('chart') {
        <div class="card">
          <ngx-skeleton-loader [theme]="{ width: '35%', height: '1.2rem' }" />
          <ngx-skeleton-loader [theme]="{ width: '70%', height: '0.8rem' }" />
          <ngx-skeleton-loader [theme]="{ height: height(), margin: 0 }" />
        </div>
      }
      @case ('map') {
        <ngx-skeleton-loader
          [theme]="{ height: height(), margin: 0, 'border-radius': 'var(--radius)' }"
        />
      }
      @case ('table') {
        <div class="card">
          <ngx-skeleton-loader [theme]="{ width: '30%', height: '1.2rem' }" />
          <ngx-skeleton-loader [theme]="{ height: '1.6rem' }" />
          <ngx-skeleton-loader
            [count]="count()"
            [theme]="{ height: '1.1rem', 'margin-bottom': '0.6rem' }"
          />
        </div>
      }
      @case ('chips') {
        <div class="card chips">
          @for (i of items(); track i) {
            <ngx-skeleton-loader
              [theme]="{ width: '4rem', height: '1.8rem', 'border-radius': '999px' }"
            />
          }
        </div>
      }
      @case ('card') {
        <div class="card">
          <ngx-skeleton-loader [theme]="{ width: '40%', height: '1.4rem' }" />
          <ngx-skeleton-loader [count]="count()" [theme]="{ height: '0.9rem' }" />
          <ngx-skeleton-loader [theme]="{ height: height(), margin: 0 }" />
        </div>
      }
      @default {
        <ngx-skeleton-loader [count]="count()" [theme]="{ height: '0.9rem' }" />
      }
    }
  `,
  styles: `
    :host {
      display: block;
      margin-bottom: 1rem;
    }

    .tiles {
      margin-bottom: 0;
    }

    .tile {
      display: flex;
      flex-direction: column;
    }

    .chips {
      display: flex;
      flex-wrap: wrap;
      gap: 0.4rem;
    }

    ngx-skeleton-loader {
      display: block;
    }

    .chips ngx-skeleton-loader {
      display: inline-block;
    }
  `,
})
export class Skeleton {
  protected readonly t = t;
  readonly kind = input<SkeletonKind>('lines');
  /** How many tiles, chips, table rows or lines. */
  readonly count = input(4);
  /** Height of a chart, map or card body, e.g. '360px'. */
  readonly height = input('220px');
  /** What is loading, for screen readers. */
  readonly label = input('');

  protected readonly items = computed(() => Array.from({ length: this.count() }, (_, i) => i));
}
