import { TestBed } from '@angular/core/testing';
import { HourChart } from './hour-chart';

describe('HourChart', () => {
  function render(inputs: Record<string, unknown>) {
    const fixture = TestBed.createComponent(HourChart);
    fixture.componentRef.setInput('from', 5);
    fixture.componentRef.setInput('to', 24);
    for (const [k, v] of Object.entries(inputs)) fixture.componentRef.setInput(k, v);
    fixture.detectChanges();
    return fixture;
  }

  it('draws series, the band and labelled reference lines, breaking lines at missing hours', () => {
    const fixture = render({
      series: [
        // 07–09 is one line; 12 stands alone after the gap, so it is drawn as a dot.
        {
          label: 'Average',
          color: 'blue',
          points: [
            { x: 7.5, y: 50 },
            { x: 8.5, y: 52 },
            { x: 12.5, y: 49 },
          ],
        },
      ],
      band: {
        label: 'Shortest–longest run',
        color: 'blue',
        points: [
          { x: 7.5, lo: 45, hi: 56 },
          { x: 8.5, lo: 47, hi: 57 },
        ],
      },
      refs: [{ label: 'Scheduled shortest', value: 45 }],
      unit: 'min',
    });
    const el: HTMLElement = fixture.nativeElement;

    expect(el.querySelectorAll('path.line')).toHaveLength(2);
    expect(el.querySelectorAll('circle:not(.marker)')).toHaveLength(1);
    expect(el.querySelectorAll('path.band')).toHaveLength(1);
    expect(el.querySelector('.ref-label')?.textContent?.trim()).toBe('Scheduled shortest 45');
    expect([...el.querySelectorAll('.legend li')].map((li) => li.textContent?.trim())).toEqual([
      'Shortest–longest run',
      'Average',
      'Scheduled shortest',
    ]);
  });

  it('reads values with the arrow keys and says when there is nothing to show', () => {
    const fixture = render({
      series: [
        {
          label: 'Average',
          color: 'blue',
          points: [
            { x: 7.5, y: 50 },
            { x: 8.5, y: 52 },
          ],
        },
      ],
      unit: 'min',
    });
    const el: HTMLElement = fixture.nativeElement;
    el.querySelector('svg')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }));
    fixture.detectChanges();
    expect(el.querySelector('.chart-tooltip')?.textContent?.replace(/\s+/g, ' ')).toContain(
      '07:00–08:00 Average 50 min',
    );

    const empty = render({ series: [], empty: 'No full runs recorded yet.' });
    expect(empty.nativeElement.querySelector('.empty')?.textContent?.trim()).toBe(
      'No full runs recorded yet.',
    );
  });
});
