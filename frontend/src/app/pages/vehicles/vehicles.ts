import { Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Store } from '@ngrx/store';
import { HasRoleDirective } from '../../core/auth/has-role.directive';
import { Vehicle } from '../../core/models/models';
import { VehiclesActions } from '../../store/vehicles/vehicles.actions';
import { vehiclesFeature } from '../../store/vehicles/vehicles.reducer';
import { filterVehicles, modelMake, options } from './vehicle-filters';

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

  /** Selected filters; '' means "all". */
  protected readonly agencyFilter = signal('');
  protected readonly makeFilter = signal('');

  protected readonly agencies = computed(() => options(this.vehicles().map((v) => v.agency)));
  protected readonly makes = computed(() => options(this.vehicles().map((v) => modelMake(v.model))));
  protected readonly filtered = computed(() =>
    filterVehicles(this.vehicles(), { agency: this.agencyFilter(), make: this.makeFilter() }),
  );
  protected readonly filtering = computed(() => !!this.agencyFilter() || !!this.makeFilter());

  /** null = form hidden, 0 = creating, otherwise id being edited. */
  protected readonly editingId = signal<number | null>(null);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    vehicleNumber: ['', [Validators.required, Validators.maxLength(32)]],
    type: ['BUS', Validators.required],
    capacity: [null as number | null, Validators.min(0)],
    agency: [null as string | null, Validators.maxLength(64)],
    model: [null as string | null, Validators.maxLength(128)],
  });

  constructor() {
    this.store.dispatch(VehiclesActions.load());
  }

  protected clearFilters(): void {
    this.agencyFilter.set('');
    this.makeFilter.set('');
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
      agency: vehicle.agency,
      model: vehicle.model,
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
    const raw = this.form.getRawValue();
    // Blank optional text fields are stored as null.
    const vehicle = { ...raw, agency: raw.agency?.trim() || null, model: raw.model?.trim() || null };
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
