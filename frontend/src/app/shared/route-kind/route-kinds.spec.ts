import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { RouteDetailApi } from '../../core/api/api.services';
import { RouteBadge, RouteKinds, classifyRoute } from './route-kinds';

@Component({
  imports: [RouteBadge],
  template: `<span class="route-badge" [appRouteBadge]="'414'">414</span>
    <span class="route-badge" [appRouteBadge]="'N1'">N1</span>
    <span class="route-badge" [appRouteBadge]="'5'">5</span>`,
})
class Badges {}

describe('Line kinds', () => {
  it('classifies a line by its name as the backend does', () => {
    expect(['3', 'N2', '103', '414', '700', '0109', 'X'].map(classifyRoute)).toEqual([
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
    const routes = vi.fn(() => of([{ name: '5', subtype: 'suburban' as const }]));
    TestBed.configureTestingModule({
      providers: [{ provide: RouteDetailApi, useValue: { routes } }],
    });
    const fixture = TestBed.createComponent(Badges);
    fixture.detectChanges();
    const colors = [...fixture.nativeElement.querySelectorAll('.route-badge')].map(
      (el: HTMLElement) => el.style.getPropertyValue('--route-color'),
    );
    expect(colors).toEqual(['var(--line-regional)', 'var(--line-night)', 'var(--line-suburban)']);
    expect(routes).toHaveBeenCalledTimes(1);
    expect(TestBed.inject(RouteKinds).color(null)).toBeNull();
    expect(TestBed.inject(RouteKinds).color('5')).toBe('#03bf03');
  });
});
