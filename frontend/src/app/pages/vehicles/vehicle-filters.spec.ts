import { Vehicle } from '../../core/models/models';
import { filterVehicles, modelMake, options } from './vehicle-filters';

const vehicle = (vehicleNumber: string, agency: string | null, model: string | null): Vehicle => ({
  id: Number(vehicleNumber),
  vehicleNumber,
  type: 'BUS',
  capacity: null,
  agency,
  model,
});

describe('vehicle filters', () => {
  const vehicles = [
    vehicle('1', 'BKM', 'Solaris Urbino 18'),
    vehicle('2', 'BKM', 'Mercedes-Benz Conecto G'),
    vehicle('3', 'WSCHODEXPRESS', 'Solaris Urbino 8,9 LE'),
    vehicle('4', null, null),
  ];

  it('takes the first word of the model', () => {
    expect(modelMake('Solaris Urbino 12 W33')).toBe('Solaris');
    expect(modelMake('  Mercedes-Benz Conecto G')).toBe('Mercedes-Benz');
    expect(modelMake(null)).toBeNull();
    expect(modelMake('   ')).toBeNull();
  });

  it('builds sorted, distinct options without empty values', () => {
    expect(options(vehicles.map((v) => v.agency))).toEqual(['BKM', 'WSCHODEXPRESS']);
    expect(options(vehicles.map((v) => modelMake(v.model)))).toEqual(['Mercedes-Benz', 'Solaris']);
  });

  it('filters by agency, model make, both, or neither', () => {
    const ids = (agency: string, make: string) => filterVehicles(vehicles, { agency, make }).map((v) => v.id);
    expect(ids('', '')).toEqual([1, 2, 3, 4]);
    expect(ids('BKM', '')).toEqual([1, 2]);
    expect(ids('', 'Solaris')).toEqual([1, 3]);
    expect(ids('BKM', 'Solaris')).toEqual([1]);
  });
});
