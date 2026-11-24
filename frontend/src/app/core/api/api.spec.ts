import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import {
  BoundaryApi,
  BrigadeApi,
  CorridorApi,
  DelayGenerationApi,
  GtfsApi,
  HotspotApi,
  LineDetailApi,
  LineStatsApi,
  TripAnomalyApi,
  TripRunApi,
  PositionHistoryApi,
  FleetApi,
  RouteApi,
  VersionApi,
  ZbiorkomApi,
} from './api.services';

/** The API clients call the backend endpoints with the parameters it expects. */
describe('API clients', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists a CRUD resource with paging, several sort orders and only the filters that are set', () => {
    TestBed.inject(RouteApi)
      .list(2, 25, ['number,asc', 'id'], { agency: 'BKM', model: null, q: '' })
      .subscribe();
    const req = http.expectOne((r) => r.url === '/api/routes');
    expect(req.request.params.get('page')).toBe('2');
    expect(req.request.params.get('size')).toBe('25');
    expect(req.request.params.getAll('sort')).toEqual(['number,asc', 'id']);
    expect(req.request.params.get('agency')).toBe('BKM');
    expect(req.request.params.has('model')).toBe(false);
    expect(req.request.params.has('q')).toBe(false);
  });

  it("asks for a brigade's day of trips, today's without a date", () => {
    const api = TestBed.inject(TripRunApi);
    api.day('103-05', '2026-11-23').subscribe();
    api.day('103-05', null).subscribe();
    expect(http.expectOne('/api/trip-runs/day?brigade=103-05&date=2026-11-23').request.method).toBe(
      'GET',
    );
    http.expectOne('/api/trip-runs/day?brigade=103-05');
  });

  it('asks for the versions', () => {
    TestBed.inject(VersionApi).versions().subscribe();
    expect(http.expectOne({ method: 'GET', url: '/api/version' })).toBeTruthy();
  });

  it('reads, creates, updates and deletes one item by id', () => {
    const api = TestBed.inject(RouteApi);
    api.get(7).subscribe();
    api.create({ number: '230' } as never).subscribe();
    api.update(7, { number: '231' } as never).subscribe();
    api.delete(7).subscribe();

    expect(http.expectOne({ method: 'GET', url: '/api/routes/7' })).toBeTruthy();
    expect(http.expectOne({ method: 'POST', url: '/api/routes' }).request.body).toEqual({
      number: '230',
    });
    expect(http.expectOne({ method: 'PUT', url: '/api/routes/7' }).request.body).toEqual({
      number: '231',
    });
    expect(http.expectOne({ method: 'DELETE', url: '/api/routes/7' })).toBeTruthy();
  });

  it('asks for position history with an optional line and operator', () => {
    const api = TestBed.inject(PositionHistoryApi);
    api.fleet({ from: 'A', to: 'B' }, { route: null, agency: null }).subscribe();
    api.delays({ from: 'A', to: 'B' }, { route: '103', agency: 'BKM' }).subscribe();
    api.routes({ from: null, to: 'B' }, 'TUROSN').subscribe();
    api.agencies().subscribe();

    const fleet = http.expectOne((r) => r.url === '/api/positions/fleet').request.params;
    expect(fleet.has('route')).toBe(false);
    expect(fleet.has('agency')).toBe(false);
    expect(fleet.get('from')).toBe('A');
    // All time: no from.
    const routes = http.expectOne((r) => r.url === '/api/positions/routes').request.params;
    expect(routes.has('from')).toBe(false);
    expect(routes.get('agency')).toBe('TUROSN');
    const delays = http.expectOne((r) => r.url === '/api/positions/delays').request.params;
    expect(delays.get('route')).toBe('103');
    expect(delays.get('agency')).toBe('BKM');
    expect(http.expectOne('/api/positions/agencies')).toBeTruthy();
  });

  it('calls the line, corridor and import endpoints, escaping the line id', () => {
    TestBed.inject(LineStatsApi).weekday().subscribe();
    TestBed.inject(LineDetailApi).lines().subscribe();
    TestBed.inject(LineDetailApi).detail('N 1/x').subscribe();
    TestBed.inject(LineDetailApi).performance('103').subscribe();
    TestBed.inject(CorridorApi).report().subscribe();
    TestBed.inject(GtfsApi).importBialystok().subscribe();

    for (const url of [
      '/api/lines/stats',
      '/api/lines',
      '/api/lines/103/performance',
      '/api/corridors',
    ]) {
      http.expectOne(url);
    }
    http.expectOne('/api/lines/N%201%2Fx/detail');
    expect(http.expectOne('/api/gtfs/bialystok/import').request.method).toBe('POST');
  });

  it("reads live positions, a brigade's schedule and the fleet for a window", () => {
    TestBed.inject(ZbiorkomApi).bialystokPositions().subscribe();
    http.expectOne('/api/zbiorkom/bialystok/positions').flush([]);

    TestBed.inject(BrigadeApi).schedule('103-05').subscribe();
    http.expectOne('/api/brigades/103-05/schedule').flush({});
    TestBed.inject(BrigadeApi).schedule('a/b').subscribe();
    http.expectOne('/api/brigades/a%2Fb/schedule').flush({});
    TestBed.inject(BrigadeApi).schedule('103-05', new Date('2026-11-16T10:00:00Z')).subscribe();
    http.expectOne('/api/brigades/103-05/schedule?at=2026-11-16T10:00:00.000Z').flush({});
    // Boundaries are fetched once and shared.
    TestBed.inject(BoundaryApi).boundaries().subscribe();
    TestBed.inject(BoundaryApi).boundaries().subscribe();
    http.expectOne('/api/boundaries').flush({ city: [], centre: [] });

    TestBed.inject(FleetApi).report({ from: null, to: 'B' }).subscribe();
    const req = http.expectOne((r) => r.url === '/api/fleet');
    expect(req.request.params.has('from')).toBe(false);
    expect(req.request.params.get('to')).toBe('B');
    expect(req.request.params.has('agency')).toBe(false);
    req.flush({});

    TestBed.inject(FleetApi).report({ from: 'A', to: 'B' }, ['BKM', 'TUROSN']).subscribe();
    const some = http.expectOne((r) => r.url === '/api/fleet');
    expect(some.request.params.getAll('agency')).toEqual(['BKM', 'TUROSN']);
    expect(some.request.params.get('from')).toBe('A');
    some.flush({});

    TestBed.inject(FleetApi).vehicle(7, { from: 'A', to: 'B' }).subscribe();
    expect(http.expectOne('/api/fleet/vehicles/7?from=A&to=B').request.method).toBe('GET');
  });

  it('reads the stop hotspots for a window, for all lines or one', () => {
    TestBed.inject(HotspotApi).report({ from: 'A', to: 'B' }).subscribe();
    const all = http.expectOne((r) => r.url === '/api/hotspots');
    expect(all.request.params.get('from')).toBe('A');
    expect(all.request.params.has('line')).toBe(false);
    all.flush({});

    TestBed.inject(HotspotApi).report({ from: 'A', to: 'B' }, '10').subscribe();
    expect(http.expectOne('/api/hotspots?from=A&to=B&line=10').request.method).toBe('GET');
  });

  it('reads delay generation for the network and for a line', () => {
    TestBed.inject(DelayGenerationApi).report({ from: null, to: 'B' }).subscribe();
    expect(http.expectOne('/api/delay-generation?to=B').request.method).toBe('GET');
    TestBed.inject(DelayGenerationApi).line({ from: 'A', to: 'B' }, 'N/1').subscribe();
    expect(http.expectOne('/api/delay-generation/lines/N%2F1?from=A&to=B').request.method).toBe(
      'GET',
    );
  });

  it('reads the abnormal trips for a window', () => {
    TestBed.inject(TripAnomalyApi).report({ from: 'A', to: 'B' }).subscribe();
    expect(http.expectOne('/api/trip-anomalies?from=A&to=B').request.method).toBe('GET');
  });
});
