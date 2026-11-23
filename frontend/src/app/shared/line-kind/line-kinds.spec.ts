import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { LineDetailApi } from '../../core/api/api.services';
import { LineBadge, LineKinds, classifyLine } from './line-kinds';

@Component({
  imports: [LineBadge],
  template: `<span class="route-badge" [appLineBadge]="'414'">414</span>
    <span class="route-badge" [appLineBadge]="'N1'">N1</span>
    <span class="route-badge" [appLineBadge]="'5'">5</span>`,
})
class Badges {}

describe('Line kinds', () => {
  it('classifies a line by its name as the backend does', () => {
    expect(['3', 'N2', '103', '414', '700', '0109', 'X'].map(classifyLine)).toEqual([
      'urban',
      'night_urban',
      'suburban',
      'regional',
      'regional',
      'suburban',
      'urban',
    ]);
  });

  it("colours a badge by its line's kind from the timetable, asked for once", () => {
    const subtypes = vi.fn(() => of({ '5': 'suburban' as const }));
    TestBed.configureTestingModule({
      providers: [{ provide: LineDetailApi, useValue: { subtypes } }],
    });
    const fixture = TestBed.createComponent(Badges);
    fixture.detectChanges();
    const colors = [...fixture.nativeElement.querySelectorAll('.route-badge')].map(
      (el: HTMLElement) => el.style.getPropertyValue('--route-color'),
    );
    expect(colors).toEqual(['var(--line-regional)', 'var(--line-night)', 'var(--line-suburban)']);
    expect(subtypes).toHaveBeenCalledTimes(1);
    expect(TestBed.inject(LineKinds).color(null)).toBeNull();
    expect(TestBed.inject(LineKinds).color('5')).toBe('#03bf03');
  });
});
