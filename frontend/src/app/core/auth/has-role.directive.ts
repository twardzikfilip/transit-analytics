import { Directive, TemplateRef, ViewContainerRef, effect, inject, input } from '@angular/core';
import { Store } from '@ngrx/store';
import { RoleName } from '../models/models';
import { authFeature } from '../../store/auth/auth.reducer';

/** Structural directive: `<button *appHasRole="'ADMIN'">` renders only for the given role(s). */
@Directive({ selector: '[appHasRole]' })
export class HasRoleDirective {
  readonly appHasRole = input.required<RoleName | RoleName[]>();

  private readonly role = inject(Store).selectSignal(authFeature.selectRole);
  private readonly template = inject(TemplateRef);
  private readonly container = inject(ViewContainerRef);

  constructor() {
    effect(() => {
      const allowed = ([] as RoleName[]).concat(this.appHasRole());
      const role = this.role();
      this.container.clear();
      if (role && allowed.includes(role)) {
        this.container.createEmbeddedView(this.template);
      }
    });
  }
}
