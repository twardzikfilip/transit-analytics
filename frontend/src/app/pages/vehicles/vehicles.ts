import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Store } from '@ngrx/store';
import { HasRoleDirective } from '../../core/auth/has-role.directive';
import { Vehicle } from '../../core/models/models';
import { VehiclesActions } from '../../store/vehicles/vehicles.actions';
import { vehiclesFeature } from '../../store/vehicles/vehicles.reducer';

@Component({
  selector: 'app-vehicles',
  imports: [ReactiveFormsModule, HasRoleDirective],
  templateUrl: './vehicles.html',
  styleUrl: './vehicles.scss',
})
export class Vehicles {
  private readonly store = inject(Store);

  protected readonly vehicles = this.store.selectSignal(vehiclesFeature.selectAll);
  protected readonly loading = this.store.selectSignal(vehiclesFeature.selectLoading);
  protected readonly error = this.store.selectSignal(vehiclesFeature.selectError);

  /** null = form hidden, 0 = creating, otherwise id being edited. */
  protected readonly editingId = signal<number | null>(null);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    vehicleNumber: ['', [Validators.required, Validators.maxLength(32)]],
    type: ['BUS', Validators.required],
    capacity: [null as number | null, Validators.min(0)],
    active: [true],
  });

  constructor() {
    this.store.dispatch(VehiclesActions.load());
  }

  protected startCreate(): void {
    this.form.reset();
    this.editingId.set(0);
  }

  protected startEdit(vehicle: Vehicle): void {
    this.form.setValue({
      vehicleNumber: vehicle.vehicleNumber,
      type: vehicle.type,
      capacity: vehicle.capacity,
      active: vehicle.active,
    });
    this.editingId.set(vehicle.id);
  }

  protected cancel(): void {
    this.editingId.set(null);
  }

  protected save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const vehicle = this.form.getRawValue();
    const id = this.editingId();
    this.store.dispatch(id ? VehiclesActions.update({ id, vehicle }) : VehiclesActions.create({ vehicle }));
    this.editingId.set(null);
  }

  protected remove(vehicle: Vehicle): void {
    if (confirm(`Delete vehicle ${vehicle.vehicleNumber}?`)) {
      this.store.dispatch(VehiclesActions.delete({ id: vehicle.id }));
    }
  }
}
