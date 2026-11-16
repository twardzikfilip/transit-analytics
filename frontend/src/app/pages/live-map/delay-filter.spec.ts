import { LivePosition } from '../../core/models/models';
import { delayedOver, parseMinDelay } from './delay-filter';

describe('live map delay filter', () => {
  const bus = (vehicleNumber: string, delaySeconds: number | null): LivePosition => ({
    vehicleId: 1,
    vehicleNumber,
    type: 'BUS',
    agency: 'BKM',
    model: null,
    routeName: '18',
    routeColor: null,
    brigade: null,
    headsign: null,
    latitude: 53.13,
    longitude: 23.16,
    bearing: null,
    delaySeconds,
    timestamp: null,
    upcomingStops: [],
  });
  const buses = [bus('early', -60), bus('on-time', 0), bus('3min', 180), bus('3min1s', 181), bus('unknown', null)];

  it('keeps everything without a filter', () => {
    expect(delayedOver(buses, null)).toHaveLength(5);
  });

  it('keeps only vehicles more than the given minutes late', () => {
    expect(delayedOver(buses, 3).map((b) => b.vehicleNumber)).toEqual(['3min1s']);
    expect(delayedOver(buses, 0).map((b) => b.vehicleNumber)).toEqual(['3min', '3min1s']);
    expect(delayedOver(buses, 0.5).map((b) => b.vehicleNumber)).toEqual(['3min', '3min1s']);
  });

  it('reads the URL value', () => {
    expect(parseMinDelay('5')).toBe(5);
    expect(parseMinDelay('2.5')).toBe(2.5);
    expect(parseMinDelay(null)).toBeNull();
    expect(parseMinDelay('')).toBeNull();
    expect(parseMinDelay('-1')).toBeNull();
    expect(parseMinDelay('abc')).toBeNull();
  });
});
