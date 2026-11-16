import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import {
  CorridorApi,
  GtfsApi,
  LineDetailApi,
  LineStatsApi,
  PositionHistoryApi,
  VehicleApi,
} from './api.services';

/** The API clients call the backend endpoints with the parameters it expects. */
describe('API clients', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists a CRUD resource with paging, several sort orders and only the filters that are set', () => {
    TestBed.inject(VehicleApi)
      .list(2, 25, ['number,asc', 'id'], { agency: 'BKM', model: null, q: '' })
      .subscribe();
    const req = http.expectOne((r) => r.url === '/api/vehicles');
    expect(req.request.params.get('page')).toBe('2');
    expect(req.request.params.get('size')).toBe('25');
    expect(req.request.params.getAll('sort')).toEqual(['number,asc', 'id']);
    expect(req.request.params.get('agency')).toBe('BKM');
    expect(req.request.params.has('model')).toBe(false);
    expect(req.request.params.has('q')).toBe(false);
  });

  it('reads, creates, updates and deletes one item by id', () => {
    const api = TestBed.inject(VehicleApi);
    api.get(7).subscribe();
    api.create({ number: '230' } as never).subscribe();
    api.update(7, { number: '231' } as never).subscribe();
    api.delete(7).subscribe();

    expect(http.expectOne({ method: 'GET', url: '/api/vehicles/7' })).toBeTruthy();
    expect(http.expectOne({ method: 'POST', url: '/api/vehicles' }).request.body).toEqual({ number: '230' });
    expect(http.expectOne({ method: 'PUT', url: '/api/vehicles/7' }).request.body).toEqual({ number: '231' });
    expect(http.expectOne({ method: 'DELETE', url: '/api/vehicles/7' })).toBeTruthy();
  });

  it('asks for position history with an optional line, and the replay with several', () => {
    const api = TestBed.inject(PositionHistoryApi);
    api.fleet('A', 'B', null).subscribe();
    api.delays('A', 'B', '103').subscribe();
    api.replay('A', 'B', ['103', '5']).subscribe();
    api.samples('A', 'B', null, 1, 50, 'delay,desc', true).subscribe();

    const fleet = http.expectOne((r) => r.url === '/api/positions/fleet');
    expect(fleet.request.params.has('route')).toBe(false);
    expect(http.expectOne((r) => r.url === '/api/positions/delays').request.params.get('route')).toBe('103');
    expect(http.expectOne((r) => r.url === '/api/positions/replay').request.params.getAll('route')).toEqual([
      '103',
      '5',
    ]);
    const samples = http.expectOne((r) => r.url === '/api/positions').request.params;
    expect(samples.get('sort')).toBe('delay,desc');
    expect(samples.get('excludeOutliers')).toBe('true');
    expect(samples.get('page')).toBe('1');
  });

  it('calls the line, corridor and import endpoints, escaping the line id', () => {
    TestBed.inject(LineStatsApi).weekday().subscribe();
    TestBed.inject(LineDetailApi).lines().subscribe();
    TestBed.inject(LineDetailApi).detail('N 1/x').subscribe();
    TestBed.inject(LineDetailApi).performance('103').subscribe();
    TestBed.inject(CorridorApi).report().subscribe();
    TestBed.inject(GtfsApi).importBialystok().subscribe();

    for (const url of ['/api/lines/stats', '/api/lines', '/api/lines/103/performance', '/api/corridors']) {
      http.expectOne(url);
    }
    http.expectOne('/api/lines/N%201%2Fx/detail');
    expect(http.expectOne('/api/gtfs/bialystok/import').request.method).toBe('POST');
  });
});
