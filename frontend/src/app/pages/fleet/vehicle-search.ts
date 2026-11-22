import { Component, computed, input, output, signal } from '@angular/core';
import { VehicleUsage } from '../../core/models/models';
import { searchVehicles } from './fleet-format';
import { t, tn } from '../../core/i18n/t';

/** Find a bus by number or model among the fleet's buses; choosing one emits its id. */
@Component({
  selector: 'app-vehicle-search',
  templateUrl: './vehicle-search.html',
  styleUrl: './vehicle-search.scss',
})
export class VehicleSearch {
  protected readonly t = t;
  /** The fleet's buses with their hours in the window. */
  readonly usage = input.required<VehicleUsage[]>();
  readonly choose = output<number>();

  protected readonly search = signal('');
  protected readonly searchOpen = signal(false);
  protected readonly matches = computed(() => searchVehicles(this.usage(), this.search()));

  protected pick(id: number): void {
    this.search.set('');
    this.searchOpen.set(false);
    this.choose.emit(id);
  }

  /** Enter picks the best match. */
  protected chooseFirst(): void {
    const first = this.matches()[0];
    if (first) this.pick(first.vehicle.id);
  }

  protected trackUsage(u: VehicleUsage): number {
    return u.vehicle.id;
  }
}
