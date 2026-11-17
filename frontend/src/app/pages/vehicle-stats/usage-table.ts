import { Component, input } from '@angular/core';
import { VehicleUsage } from '../../core/models/models';

/** A top 10 of vehicles: by hours in service, or by the number of different lines served. */
@Component({
  selector: 'app-usage-table',
  template: `
    <section class="card">
      <h2>{{ title() }}</h2>
      <p class="muted">{{ description() }}</p>
      <div class="data-table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th class="index">#</th>
              <th>Vehicle</th>
              <th>Model</th>
              <th>Operator</th>
              @if (by() === 'hours') {
                <th class="num">Hours</th>
                <th class="num">Days</th>
                <th>Lines</th>
              } @else {
                <th class="num">Lines</th>
                <th>Which</th>
                <th class="num">Hours</th>
              }
            </tr>
          </thead>
          <tbody>
            @for (u of rows(); track u.vehicle.id; let i = $index) {
              <tr>
                <td class="index muted">{{ i + 1 }}</td>
                <td>{{ u.vehicle.number }}</td>
                <td>{{ u.vehicle.model ?? '—' }}</td>
                <td>{{ u.vehicle.agency ?? '—' }}</td>
                @if (by() === 'hours') {
                  <td class="num">{{ u.hours.toFixed(1) }}</td>
                  <td class="num">{{ u.days }}</td>
                  <td>{{ u.lines.join(', ') }}</td>
                } @else {
                  <td class="num">{{ u.lines.length }}</td>
                  <td>{{ u.lines.join(', ') }}</td>
                  <td class="num">{{ u.hours.toFixed(1) }}</td>
                }
              </tr>
            } @empty {
              <tr>
                <td colspan="7" class="muted">No vehicles in service in this window.</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    </section>
  `,
})
export class UsageTable {
  readonly title = input.required<string>();
  readonly description = input.required<string>();
  readonly rows = input.required<VehicleUsage[]>();
  /** What the list ranks by; that column comes first. */
  readonly by = input<'hours' | 'lines'>('hours');
}
