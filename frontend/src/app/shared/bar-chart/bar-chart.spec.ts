import { Component, signal } from '@angular/core';
import { renderPage, text } from '../../testing/page-testing';
import { Bar, BarChart, valueTicks } from './bar-chart';

@Component({
  imports: [BarChart],
  template: `<app-bar-chart
    [bars]="bars()"
    label="Hours per bus"
    [format]="hours"
    [selected]="2"
    [selectable]="true"
    (picked)="picked = $event"
  />`,
})
class Host {
  readonly bars = signal<Bar[]>([
    { key: 1, label: '230', value: 12.5, title: '230: 12.5 h', detail: 'lines 103' },
    { key: 2, label: '9', value: 3, title: '9: 3 h', marked: true },
    { key: 3, label: '17', value: 0, title: '17: 0 h' },
  ]);
  readonly hours = (v: number) => `${v} h`;
  picked: string | number | null = null;
}

describe('bar chart', () => {
  it('picks nice ticks for the value axis', () => {
    expect(valueTicks(0)).toEqual([0]);
    expect(valueTicks(12.5)).toEqual([0, 5, 10, 15]);
    expect(valueTicks(3)).toEqual([0, 1, 2, 3]);
    expect(valueTicks(0.4)).toEqual([0, 0.1, 0.2, 0.3, 0.4]);
  });

  it('draws a bar per item, marks and selection, a tooltip on hover, and picks on click', () => {
    const { el, fixture, render } = renderPage(Host, {});
    const bars = el.querySelectorAll('rect.bar');
    expect(bars).toHaveLength(3);
    expect(bars[1].classList).toContain('selected');
    expect(el.querySelectorAll('circle.mark')).toHaveLength(1);
    expect([...el.querySelectorAll('text.axis-label')].map(text)).toContain('15 h');

    const hits = el.querySelectorAll('rect.hit');
    hits[0].dispatchEvent(new Event('pointerenter'));
    render();
    expect(text(el.querySelector('.chart-tooltip'))).toBe('230: 12.5 hlines 103');
    (hits[2] as SVGElement).dispatchEvent(new Event('click'));
    expect(fixture.componentInstance.picked).toBe(3);
    el.querySelector('svg')!.dispatchEvent(new Event('pointerleave'));
    render();
    expect(el.querySelector('.chart-tooltip')).toBeNull();
  });
});
