import { TestBed } from '@angular/core/testing';
import { Pager, rowNumber } from './pager';

describe('Pager', () => {
  it('shows the position and moves between pages, disabling buttons at the ends', () => {
    const fixture = TestBed.createComponent(Pager);
    fixture.componentRef.setInput('page', {
      number: 0,
      size: 50,
      totalElements: 1083,
      totalPages: 22,
    });
    fixture.componentRef.setInput('noun', 'stops');
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    const pages: number[] = [];
    fixture.componentInstance.pageChange.subscribe((p: number) => pages.push(p));
    const buttons = () => [...el.querySelectorAll('button')] as HTMLButtonElement[];

    expect(el.textContent?.replace(/\s+/g, ' ')).toContain('Page 1 of 22 · 1,083 stops');
    expect(buttons().map((b) => b.disabled)).toEqual([true, true, false, false]);
    buttons()[2].click();
    buttons()[3].click();
    expect(pages).toEqual([1, 21]);

    fixture.componentRef.setInput('page', {
      number: 0,
      size: 50,
      totalElements: 12,
      totalPages: 1,
    });
    fixture.detectChanges();
    expect(buttons()).toHaveLength(0);
    expect(el.textContent?.trim()).toBe('12 stops');
  });
});

describe('rowNumber', () => {
  it('counts rows across pages', () => {
    expect(rowNumber({ number: 1, size: 50, totalElements: 120, totalPages: 3 }, 0)).toBe(51);
    expect(rowNumber(null, 4)).toBe(5);
  });
});
