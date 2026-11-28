import { LivePosition } from '../../../core/models/models';
import {
  agencies,
  delayedOver,
  ofAgency,
  ofRouteType,
  parseRouteType,
  parseMinDelay,
} from './delay-filter';

describe('live map delay filter', () => {
  const bus = (vehicleNumber: string, delaySeconds: number | null): LivePosition => ({
    vehicleId: 1,
    vehicleNumber,
    type: 'BUS',
    agency: 'BKM',
    model: null,
    routeName: '18',
    subtype: 'urban',
    brigade: null,
    headsign: null,
    latitude: 53.13,
    longitude: 23.16,
    bearing: null,
    delaySeconds,
    timestamp: null,
    upcomingStops: [],
  });
  const buses = [
    bus('early', -60),
    bus('on-time', 0),
    bus('3min', 180),
    bus('3min1s', 181),
    bus('unknown', null),
  ];

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

  it('filters by operator and lists the operators, keeping the chosen one', () => {
    const a = { ...bus('1', 0), agency: 'BKM' };
    const b = { ...bus('2', 0), agency: 'WSCHODEXPRESS' };
    expect(ofAgency([a, b], 'BKM')).toEqual([a]);
    expect(ofAgency([a, b], '')).toEqual([a, b]);
    expect(agencies([b, a, a])).toEqual(['BKM', 'WSCHODEXPRESS']);
    expect(agencies([a], 'TUROSN')).toEqual(['BKM', 'TUROSN']);
  });

  it('filters by kind of line, night lines counting as urban', () => {
    const urban = { ...bus('u', 0), subtype: 'urban' as const };
    const night = { ...bus('n', 0), subtype: 'night_urban' as const };
    const suburban = { ...bus('s', 0), subtype: 'suburban' as const };
    const regional = { ...bus('r', 0), subtype: 'regional' as const };
    const unknown = { ...bus('x', 0), subtype: null };
    const all = [urban, night, suburban, regional, unknown];
    expect(ofRouteType(all, null)).toEqual(all);
    expect(ofRouteType(all, 'urban')).toEqual([urban, night]);
    expect(ofRouteType(all, 'suburban')).toEqual([suburban]);
    expect(ofRouteType(all, 'regional')).toEqual([regional]);
    expect(parseRouteType('suburban')).toBe('suburban');
    expect(parseRouteType('night_urban')).toBeNull();
    expect(parseRouteType(null)).toBeNull();
  });
});
