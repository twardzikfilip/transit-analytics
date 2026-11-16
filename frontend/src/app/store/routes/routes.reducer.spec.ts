import { Route } from '../../core/models/models';
import { RoutesActions } from './routes.actions';
import { routesFeature } from './routes.reducer';

describe('routes reducer', () => {
  const route = (id: number, name: string): Route => ({
    id,
    gtfsId: name,
    agencyId: 1,
    agencyName: 'BKM',
    name,
    shortName: null,
    longName: name,
    description: null,
    routeType: 3,
    url: null,
    color: null,
    textColor: null,
    sortOrder: null,
    subtype: 'urban',
  });

  it('keeps routes in natural line order', () => {
    const state = routesFeature.reducer(
      undefined,
      RoutesActions.loadSuccess({ routes: [route(1, '10'), route(2, 'N1'), route(3, '2'), route(4, '100')] }),
    );
    expect(routesFeature.selectAll.projector(state).map((r) => r.name)).toEqual(['2', '10', '100', 'N1']);
  });
});
