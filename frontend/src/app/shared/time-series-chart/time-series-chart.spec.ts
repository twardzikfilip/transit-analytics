import { TestBed } from '@angular/core/testing';
import { ChartPoint } from './chart-geometry';
import { TimeSeriesChart } from './time-series-chart';

describe('TimeSeriesChart', () => {
  const T0 = Date.UTC(2026, 10, 16, 10, 0);
  const at = (seconds: number, value: number): ChartPoint => ({ time: T0 + seconds * 1000, value });

  function render(points: ChartPoint[], stepMs = 20_000) {
    const fixture = TestBed.createComponent(TimeSeriesChart);
    fixture.componentRef.setInput('points', points);
    fixture.componentRef.setInput('from', T0);
    fixture.componentRef.setInput('to', T0 + 600_000);
    fixture.componentRef.setInput('stepMs', stepMs);
    fixture.componentRef.setInput('unit', 'vehicles');
    fixture.componentRef.setInput('label', 'Vehicles running');
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  it('draws one line across a missed poll, breaks it at a long pause, and keeps a lone point as a dot', () => {
    const { el } = render([at(0, 10), at(20, 12), at(60, 11), at(300, 9), at(320, 8), at(500, 7)]);

    expect(el.querySelectorAll('path.line')).toHaveLength(2);
    expect(el.querySelectorAll('circle.dot')).toHaveLength(1);
    expect(el.querySelector('svg')!.getAttribute('aria-label')).toBe(
      'Vehicles running: 6 points, from 7 to 12 vehicles.',
    );
  });

  it('walks the points with the keyboard and shows the value in a tooltip', () => {
    const { el, fixture } = render([at(0, 10), at(20, 12), at(40, 11)]);
    const svg = el.querySelector('svg')!;
    const key = (k: string) => {
      svg.dispatchEvent(new KeyboardEvent('keydown', { key: k, cancelable: true }));
      fixture.detectChanges();
    };

    key('ArrowRight');
    expect(el.querySelector('.chart-tooltip strong')!.textContent!.trim()).toBe('10 vehicles');
    key('End');
    expect(el.querySelector('.chart-tooltip strong')!.textContent!.trim()).toBe('11 vehicles');
    key('ArrowLeft');
    expect(el.querySelector('.chart-tooltip strong')!.textContent!.trim()).toBe('12 vehicles');
    key('Home');
    expect(el.querySelector('.chart-tooltip strong')!.textContent!.trim()).toBe('10 vehicles');
    key('Escape');
    expect(el.querySelector('.chart-tooltip')).toBeNull();
  });

  it('says when nothing was recorded', () => {
    const { el } = render([]);
    expect(el.querySelector('.empty')!.textContent).toContain('Nothing was recorded');
    expect(el.querySelector('svg')!.getAttribute('aria-label')).toBe(
      'Vehicles running: no data in this time range.',
    );
  });
});
