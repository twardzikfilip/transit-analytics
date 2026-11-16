import { Vehicle } from '../../core/models/models';
import { VehiclesActions } from '../../store/vehicles/vehicles.actions';
import { vehiclesFeature } from '../../store/vehicles/vehicles.reducer';
import { renderPage, signedIn, stateAfter, text } from '../../testing/page-testing';
import { Vehicles } from './vehicles';

const vehicles: Vehicle[] = [
  { id: 1, vehicleNumber: '230', type: 'BUS', capacity: 90, agency: 'BKM', model: 'Solaris Urbino 12' },
  { id: 2, vehicleNumber: '009', type: 'BUS', capacity: null, agency: 'BKM', model: 'Yutong U12' },
  { id: 3, vehicleNumber: 'R1', type: 'BUS', capacity: null, agency: 'TUROSN', model: null },
];

describe('Vehicles page', () => {
  const state = (role: 'ADMIN' | 'GUEST') => ({
    ...signedIn(role),
    ...stateAfter(vehiclesFeature, VehiclesActions.loadSuccess({ vehicles })),
  });

  it('loads the fleet and filters it by agency', () => {
    const { el, store, render } = renderPage(Vehicles, state('GUEST'));
    expect(store.dispatch).toHaveBeenCalledWith(VehiclesActions.load());
    expect(el.querySelectorAll('tbody tr')).toHaveLength(3);
    expect(text(el.querySelector('.count'))).toBe('3 of 3 vehicles');

    const agency = el.querySelector('select') as HTMLSelectElement;
    agency.value = 'TUROSN';
    agency.dispatchEvent(new Event('change'));
    render();
    expect(text(el.querySelector('.count'))).toBe('1 of 3 vehicles');
  });

  it('shows editing only to admins', () => {
    expect(renderPage(Vehicles, state('GUEST')).el.querySelector('button.primary')).toBeNull();
  });

  it('saves a new vehicle, storing blank optional fields as empty', () => {
    const { el, store, render } = renderPage(Vehicles, state('ADMIN'));
    (el.querySelector('button.primary') as HTMLButtonElement).click();
    render();
    const number = el.querySelector('input[formcontrolname=vehicleNumber]') as HTMLInputElement;
    number.value = '512';
    number.dispatchEvent(new Event('input'));
    const agency = el.querySelector('input[formcontrolname=agency]') as HTMLInputElement;
    agency.value = '  ';
    agency.dispatchEvent(new Event('input'));
    el.querySelector('form')!.dispatchEvent(new Event('submit'));

    expect(store.dispatch).toHaveBeenCalledWith(
      VehiclesActions.create({
        vehicle: { vehicleNumber: '512', type: 'BUS', capacity: null, agency: null, model: null },
      }),
    );
  });

  it('asks before deleting', () => {
    const { el, store } = renderPage(Vehicles, state('ADMIN'));
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const row = [...el.querySelectorAll('tbody tr')].find((r) => text(r.querySelector('td:nth-child(2)')) === '230')!;
    const remove = row.querySelector('button.danger') as HTMLButtonElement;
    remove.click();
    expect(store.dispatch).not.toHaveBeenCalledWith(VehiclesActions.delete({ id: 1 }));

    confirm.mockReturnValue(true);
    remove.click();
    expect(confirm).toHaveBeenLastCalledWith('Delete vehicle 230?');
    expect(store.dispatch).toHaveBeenCalledWith(VehiclesActions.delete({ id: 1 }));
    confirm.mockRestore();
  });
});

