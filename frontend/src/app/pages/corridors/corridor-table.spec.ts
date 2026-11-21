import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Corridor } from '../../core/models/models';
import { CorridorTable } from './corridor-table';

const corridor = (from: string, buses: number, km: number, oneWay = false): Corridor => ({
  from,
  to: 'Z',
  stops: [from, 'B', 'C', 'Z'],
  lines: ['1', '2', '4'],
  throughLines: ['1', '2'],
  km,
  buses,
  oneWay,
  peakIntervalMin: 3.1,
  offPeakIntervalMin: 3.8,
  peakCv: 0.76,
  offPeakCv: null,
});

describe('CorridorTable', () => {
  it('lists the busiest first, sorts by a column and marks lines running only part of a corridor', () => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(CorridorTable);
    fixture.componentRef.setInput('corridors', [
      corridor('Short', 600, 1.3),
      corridor('Long', 300, 4.2, true),
    ]);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    const firstRow = () => el.querySelector('tbody tr')?.textContent?.replace(/\s+/g, ' ') ?? '';

    expect(firstRow()).toContain('Short ⇄ Z');
    const length = [...el.querySelectorAll('button.sort')].find(
      (b) => b.textContent?.trim() === 'Length',
    );
    (length as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(firstRow()).toContain('Long → Z');
    expect(firstRow()).toContain('one way');

    const badges = [...el.querySelectorAll('tbody tr:first-child .route-badge')];
    expect(badges.map((b) => b.classList.contains('partial'))).toEqual([false, false, true]);
    expect(el.querySelector('tbody tr:first-child .grade')?.textContent?.trim()).toBe('F');
  });
});
