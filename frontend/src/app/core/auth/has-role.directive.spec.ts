import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MockStore, provideMockStore } from '@ngrx/store/testing';
import { authFeature } from '../../store/auth/auth.reducer';
import { HasRoleDirective } from './has-role.directive';

@Component({
  imports: [HasRoleDirective],
  template: `<button *appHasRole="'ADMIN'">Delete</button><span *appHasRole="['ADMIN', 'GUEST']">View</span>`,
})
class Host {}

describe('HasRoleDirective', () => {
  it('shows content only for the listed roles and follows role changes', () => {
    TestBed.configureTestingModule({ providers: [provideMockStore()] });
    const store = TestBed.inject(MockStore);
    const role = store.overrideSelector(authFeature.selectRole, 'GUEST');
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;

    expect(el.querySelector('button')).toBeNull();
    expect(el.querySelector('span')?.textContent).toBe('View');

    role.setResult('ADMIN');
    store.refreshState();
    fixture.detectChanges();
    expect(el.querySelector('button')?.textContent).toBe('Delete');

    role.setResult(null);
    store.refreshState();
    fixture.detectChanges();
    expect(el.querySelector('button')).toBeNull();
    expect(el.querySelector('span')).toBeNull();
  });
});
